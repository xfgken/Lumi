package com.xfgken.Lumi.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.xfgken.Lumi.LumiApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VPN 控制中枢（ViewModel 之上的单例门面，Service 与 UI 共用）。
 *
 * 职责：
 *  - 请求 VPN 授权并启动 LumiVpnService（前台服务）
 *  - 暴露连接状态、会话时长、错误信息
 *  - 停止连接
 */
object VpnController {

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 待处理 VPN 授权 Intent（由 Activity 的 launcher 消费） */
    private val _prepareIntent = MutableStateFlow<Intent?>(null)
    val prepareIntent: StateFlow<Intent?> = _prepareIntent.asStateFlow()

    fun consumePrepareIntent() {
        _prepareIntent.value = null
    }

    /** 由 Service 上报 */
    @Volatile
    var sessionId: Long = -1L
        private set

    @Volatile
    var connectedAtMs: Long = 0L
        private set

    @Volatile
    var lastDownBytes: Long = 0L

    @Volatile
    var lastUpBytes: Long = 0L

    @Volatile
    var activeConns: Int = 0

    // ---- 节点累计流量（持久化：本次连接流量 + 该配置历史累计） ----
    @Volatile
    private var currentNodeKey: String? = null

    /** 该配置历史累计流量（不含本次会话，连接期间实时含会话） */
    private val _nodeTotalBytes = MutableStateFlow(0L)
    val nodeTotalBytes: StateFlow<Long> = _nodeTotalBytes.asStateFlow()

    /** 本次会话累计流量（Service 每轮上报 down+up） */
    @Volatile
    var sessionBytes: Long = 0L
        private set

    /** 距上次持久化以来的增量阈值（字节），超过则落盘 */
    private const val PERSIST_THRESHOLD = 512L * 1024

    private fun trafficPrefs() =
        LumiApp.instance.getSharedPreferences("lumi_traffic", android.content.Context.MODE_PRIVATE)

    fun trafficKey(node: com.xfgken.Lumi.model.NodeConfig): String =
        "node:${node.server}:${node.port}"

    /** Service 在连接成功后调用：记录当前节点并载入历史累计 */
    fun onSessionStart(node: com.xfgken.Lumi.model.NodeConfig) {
        currentNodeKey = trafficKey(node)
        sessionBytes = 0L
        _nodeTotalBytes.value = trafficPrefs().getLong(currentNodeKey!!, 0L)
    }

    /** Service 每轮统计调用：更新“本次会话/该配置累计”两个展示值 */
    fun reportSessionBytes(sessionBytes: Long) {
        this.sessionBytes = sessionBytes
        val key = currentNodeKey ?: return
        _nodeTotalBytes.value = trafficPrefs().getLong(key, 0L) + sessionBytes
    }

    /** 会话结束/断开时把本次流量累加持久化 */
    fun persistSessionTraffic() {
        val key = currentNodeKey ?: return
        if (sessionBytes <= 0) return
        val total = trafficPrefs().getLong(key, 0L) + sessionBytes
        trafficPrefs().edit().putLong(key, total).apply()
        currentNodeKey = null
        sessionBytes = 0L
    }

    fun onServiceStarted(sid: Long) {
        sessionId = sid
        connectedAtMs = System.currentTimeMillis()
        lastDownBytes = 0L
        lastUpBytes = 0L
        activeConns = 0
        _state.value = VpnState.CONNECTED
        _error.value = null
    }

    fun onServiceStopped() {
        sessionId = -1L
        // 断开/失败时落盘本次流量
        persistSessionTraffic()
        _state.value = VpnState.DISCONNECTED
    }

    fun onError(msg: String) {
        _error.value = msg
    }

    /** 清除错误横幅（超时自动隐藏 / 下次操作前复位） */
    fun clearError() {
        _error.value = null
    }

    /** 发起连接：prepare -> startForegroundService */
    suspend fun connect(context: Context): Boolean {
        if (_state.value == VpnState.CONNECTED || _state.value == VpnState.CONNECTING) return true

        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            // 需要用户授权：把 Intent 交给 Activity launcher（不能用 Application context
            // 直接 startActivity，会因缺 FLAG_ACTIVITY_NEW_TASK 抛异常闪退）
            _state.value = VpnState.CONNECTING
            _prepareIntent.value = vpnIntent
            return false
        }
        return doStart(context)
    }

    /** prepare 回调后再次触发 */
    suspend fun connectAfterPrepare(context: Context): Boolean = doStart(context)

    private suspend fun doStart(context: Context): Boolean {
        return try {
            _state.value = VpnState.CONNECTING
            _error.value = null
            val intent = Intent(context, LumiVpnService::class.java)
                .putExtra(LumiVpnService.EXTRA_ACTION, LumiVpnService.ACTION_CONNECT)
            context.startForegroundService(intent)
            true
        } catch (e: Exception) {
            _state.value = VpnState.DISCONNECTED
            _error.value = e.message
            false
        }
    }

    /** 断开连接 */
    fun disconnect(context: Context) {
        if (_state.value != VpnState.CONNECTED && _state.value != VpnState.CONNECTING) return
        _state.value = VpnState.DISCONNECTING
        try {
            val intent = Intent(context, LumiVpnService::class.java)
                .putExtra(LumiVpnService.EXTRA_ACTION, LumiVpnService.ACTION_DISCONNECT)
            context.startService(intent)
        } catch (e: Exception) {
            // 服务未运行时直接复位
            _state.value = VpnState.DISCONNECTED
        }
    }

    /** 进程内快速复位（服务异常退出时调用） */
    fun forceReset() {
        _state.value = VpnState.DISCONNECTED
        sessionId = -1L
    }

    fun appContext(): Context = LumiApp.instance
}