package com.xfgken.Lumi.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xfgken.Lumi.LumiApp
import com.xfgken.Lumi.data.LogEntity
import com.xfgken.Lumi.model.NodeConfig
import com.xfgken.Lumi.utils.PingResult
import com.xfgken.Lumi.utils.PingUtil
import com.xfgken.Lumi.vpn.ProxyMode
import com.xfgken.Lumi.vpn.ProxyModeHolder
import com.xfgken.Lumi.vpn.RulePolicyHolder
import com.xfgken.Lumi.vpn.VpnController
import com.xfgken.Lumi.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 首页/全局 ViewModel：连接状态机、节点管理、实时统计、设置。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LumiApp.instance.repository
    private val settings = LumiApp.instance.settings

    // ---- 节点 ----
    val nodes: StateFlow<List<NodeConfig>> = repo.observeNodes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- VPN 状态 ----
    val vpnState: StateFlow<VpnState> = VpnController.state

    val errorMessage: StateFlow<String?> = VpnController.error

    /** 该配置累计流量（历史 + 本次会话实时） */
    val nodeTotalBytes: StateFlow<Long> = VpnController.nodeTotalBytes

    /** VPN 授权 Intent（Activity launcher 消费） */
    val prepareIntent: StateFlow<Intent?> = VpnController.prepareIntent

    fun consumePrepareIntent() = VpnController.consumePrepareIntent()

    // ---- 统计 ----
    private val _stats = MutableStateFlow(
        VpnStats(downBytes = 0, upBytes = 0, downSpeed = 0f, upSpeed = 0f, activeConns = 0)
    )
    val stats: StateFlow<VpnStats> = _stats

    /** 实时速率历史（动态曲线用，每 0.1s 一个采样，窗口 300 点 ≈ 30s） */
    private val _speedHistory = MutableStateFlow<List<SpeedSample>>(emptyList())
    val speedHistory: StateFlow<List<SpeedSample>> = _speedHistory

    /** 连接时长秒 */
    val connectedSeconds: StateFlow<Long> = MutableStateFlow(0L)

    // ---- 设置 ----
    // 官方 golib 核心随 AAR 打包，无独立加载步骤
    private val _coreLoaded = MutableStateFlow(true)
    val coreLoaded: StateFlow<Boolean> = _coreLoaded

    val settingsUi: StateFlow<SettingsUiState> =
        combine(settings.proxyMode, settings.themeMode) { mode, theme ->
            SettingsUiState(
                proxyMode = mode,
                themeMode = theme,
                coreLoaded = _coreLoaded.value
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        // 统计轮询（0.1s 采样）+ 时长计算 + 速率历史（动态曲线）
        viewModelScope.launch {
            var lastDown = VpnController.lastDownBytes
            var lastUp = VpnController.lastUpBytes
            var lastTs = System.currentTimeMillis()
            // EMA 低通平滑：抑制瞬时跳变，曲线更丝滑（α≈0.45 → 时间常数≈220ms）
            var hasSmoothed = false
            var smDown = 0f
            var smUp = 0f
            val history = ArrayList<SpeedSample>(HISTORY_MAX)
            while (true) {
                delay(POLL_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val dt = ((now - lastTs).coerceAtLeast(1)) / 1000f

                // 从控制器读取（Service 每 ~100ms 更新）
                val down = VpnController.lastDownBytes
                val up = VpnController.lastUpBytes
                val rawDown = ((down - lastDown).coerceAtLeast(0)) / dt
                val rawUp = ((up - lastUp).coerceAtLeast(0)) / dt
                // 一阶低通（EMA）
                val smoothDown = if (!hasSmoothed) rawDown else smDown * (1f - SMOOTH_ALPHA) + rawDown * SMOOTH_ALPHA
                val smoothUp = if (!hasSmoothed) rawUp else smUp * (1f - SMOOTH_ALPHA) + rawUp * SMOOTH_ALPHA
                smDown = smoothDown
                smUp = smoothUp
                hasSmoothed = true

                _stats.value = VpnStats(
                    downBytes = down,
                    upBytes = up,
                    downSpeed = smoothDown,
                    upSpeed = smoothUp,
                    activeConns = VpnController.activeConns
                )
                history += SpeedSample(upSpeed = smoothUp, downSpeed = smoothDown, timeMs = now)
                if (history.size > HISTORY_MAX) {
                    history.removeAt(0)
                }
                _speedHistory.value = history.toList()
                lastDown = down
                lastUp = up
                lastTs = now

                // 连接时长
                val connectedAt = VpnController.connectedAtMs
                if (connectedAt > 0 && VpnController.state.value == VpnState.CONNECTED) {
                    (connectedSeconds as MutableStateFlow).value = (now - connectedAt) / 1000
                } else {
                    (connectedSeconds as MutableStateFlow).value = 0L
                }
            }
        }

        // 设置 -> 进程内缓存（供 VPN Service JNI 使用）
        viewModelScope.launch {
            settings.proxyMode.collect { ProxyModeHolder.current = it }
        }
        viewModelScope.launch {
            settings.chinaDirect.collect { RulePolicyHolder.cnDirect = it }
        }
        viewModelScope.launch {
            settings.abroadProxy.collect { RulePolicyHolder.abroadProxy = it }
        }
    }

    // ---- 连接控制 ----

    fun onConnectClick() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val state = VpnController.state.value
            when (state) {
                VpnState.CONNECTED, VpnState.CONNECTING -> VpnController.disconnect(app)
                // 正在断开：忽略本次点击——此时若立即启动新连接，会与旧 stop()
                // 并发操作同一核心实例，可能把新连接一起掐断（快速连点竞态）
                VpnState.DISCONNECTING -> Unit
                else -> {
                    // 无配置时直接给红色错误提示，不进入连接流程（避免页面闪烁）
                    val nodesNow = withContext(Dispatchers.IO) { repo.getAll() }
                    if (nodesNow.isEmpty()) {
                        VpnController.onError("未选择节点，请先在首页添加并选择配置")
                        return@launch
                    }
                    VpnController.connect(app)
                }
            }
        }
    }

    /** VPN 授权 ActivityResult 回调后调用 */
    fun onVpnPrepared() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            VpnController.connectAfterPrepare(app)
        }
    }

    // ---- 节点操作 ----

    fun addNode(node: NodeConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.insert(node)
        }
    }

    fun updateNode(node: NodeConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.update(node)
        }
    }

    fun deleteNode(node: NodeConfig) {
        // 删除的是当前连接/连接中的配置：先自动断开
        val st = VpnController.state.value
        if (node.isSelected && (st == VpnState.CONNECTED || st == VpnState.CONNECTING)) {
            VpnController.disconnect(getApplication())
        }
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(node)
        }
    }

    fun selectNode(nodeId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.select(nodeId)
        }
    }

    /**
     * 点击另一个配置：若 VPN 连接中则实时切换（选中新节点 -> 断开等待复位 -> 自动重连），
     * 不需要用户手动断开再连。
     */
    fun onNodeClick(node: NodeConfig) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val st = VpnController.state.value
            val switching = st == VpnState.CONNECTED || st == VpnState.CONNECTING || st == VpnState.DISCONNECTING
            withContext(Dispatchers.IO) { repo.select(node.id) }
            if (switching) {
                VpnController.disconnect(app)
                // 等待服务真正复位后再发起新连接（上限 8s）
                withTimeoutOrNull(8000L) {
                    VpnController.state.first { it == VpnState.DISCONNECTED }
                }
                VpnController.connect(app)
            }
        }
    }

    /** 测节点延迟：结果自动写入节点（超时=-2 / 不可达=-3 / 成功=ms）；回调在主线程 */
    fun pingNode(node: NodeConfig, onResult: (Int?) -> Unit = {}) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                PingUtil.measure(getApplication(), node.server, node.port)
            }
            val newPing = when (r) {
                is PingResult.Ok -> r.ms
                PingResult.Timeout -> NodeConfig.PING_TIMEOUT
                PingResult.Unreachable -> NodeConfig.PING_UNREACHABLE
            }
            withContext(Dispatchers.IO) {
                repo.update(node.copy(pingMs = newPing))
            }
            onResult((r as? PingResult.Ok)?.ms) // viewModelScope 默认主线程
        }
    }

    // ---- 日志 ----
    val logs: StateFlow<List<LogEntity>> = repo.observeLogs(LOG_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) { repo.clearLogs() }
    }

    // ---- 设置操作 ----
    /** 切换代理模式。规则模式下「中国直连/国外代理」附属开关固定开启（UI 已隐藏开关）。 */
    fun setProxyMode(mode: Int) = viewModelScope.launch {
        if (mode == ProxyMode.RULE) {
            settings.setChinaDirect(true)
            settings.setAbroadProxy(true)
        }
        settings.setProxyMode(mode)
    }

    /** 深/浅色一键切换（默认浅色；浅色<->深色，兼容旧"跟随系统"数据按浅色处理） */
    fun toggleTheme() = viewModelScope.launch {
        val cur = settings.themeMode.first()
        settings.setThemeMode(if (cur == 2) 1 else 2)
    }

    /**
     * 编辑"正在连接/连接中"的选中节点并保存后调用：
     * 断开 → 等状态复位 → 自动重连，让新配置立即生效（无需手动再点一次连接）。
     */
    fun reconnectAfterEdit() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val st = VpnController.state.value
            if (st == VpnState.DISCONNECTED) return@launch
            VpnController.disconnect(app)
            withTimeoutOrNull(8000L) {
                VpnController.state.first { it == VpnState.DISCONNECTED }
            }
            VpnController.connect(app)
        }
    }

    /** 清除顶部错误横幅（供超时自动隐藏） */
    fun clearError() = VpnController.clearError()

    // ---- 格式化 ----

    companion object {
        /** UI 统计轮询间隔（0.1s 采样，动态曲线丝滑） */
        private const val POLL_INTERVAL_MS = 100L

        /** EMA 低通系数（0~1，越小越平滑） */
        private const val SMOOTH_ALPHA = 0.35f

        /** 日志页展示上限（对齐数据库 trim 保留量，展示全部保留日志） */
        private const val LOG_LIMIT = 2000

        /** 速率历史窗口长度（0.1s × 300 ≈ 30s 曲线） */
        private const val HISTORY_MAX = 300

        fun formatBytes(bytes: Long): String {
            if (bytes < 0) return "0 B"
            val kb = bytes / 1024f
            val mb = kb / 1024f
            val gb = mb / 1024f
            return when {
                gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$bytes B"
            }
        }

        fun formatSpeed(bytesPerSec: Float): String {
            if (bytesPerSec <= 0) return "0 B/s"
            val kb = bytesPerSec / 1024f
            val mb = kb / 1024f
            return when {
                mb >= 1 -> String.format(Locale.US, "%.1f MB/s", mb)
                kb >= 1 -> String.format(Locale.US, "%.1f KB/s", kb)
                else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
            }
        }

        fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            // 统一精确到时分秒：HH:MM:SS
            return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        }
    }
}

data class VpnStats(
    val downBytes: Long = 0,
    val upBytes: Long = 0,
    val downSpeed: Float = 0f,
    val upSpeed: Float = 0f,
    val activeConns: Int = 0
)

/** 单次速率采样（动态曲线用）；timeMs = 采样时刻（平滑滚动 x 轴基准） */
data class SpeedSample(
    val upSpeed: Float = 0f,
    val downSpeed: Float = 0f,
    val timeMs: Long = 0L,
)

data class SettingsUiState(
    val proxyMode: Int = ProxyMode.RULE,
    val themeMode: Int = 1,
    val coreLoaded: Boolean = true
)