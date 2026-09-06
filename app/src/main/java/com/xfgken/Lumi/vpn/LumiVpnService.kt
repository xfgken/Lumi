package com.xfgken.Lumi.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.xfgken.Lumi.LumiApp
import com.xfgken.Lumi.MainActivity
import com.xfgken.Lumi.R
import com.xfgken.Lumi.core.Hysteria2UriParser
import com.xfgken.Lumi.model.NodeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.DnsTransport
import ru.shapovalov.hysteria.api.DnsUpstream
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.TunConfig
import ru.shapovalov.hysteria.parseHysteriaUri

/**
 * Lumi VPN 前台服务 —— 生命周期与 Bedlam 完全一致：
 *  - onRevoke() = stop(DisconnectReason.REVOKED)
 *  - stop(): 复位 UI -> suspend client.stop()（等待核心/TUN 完全释放）-> stopSelf
 *  - onDestroy: 兜底 client.shutdown()
 *  不引入任何自定义轮询/监听（Bedlam 没有，避免干扰其他 VPN 客户端）。
 */
@SuppressLint("VpnServicePolicy")
class LumiVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var worker: Job? = null
    private var stateJob: Job? = null
    private var logJob: Job? = null
    private var statsJob: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var currentNode: NodeConfig? = null

    private var lastStartId: Int = 0

    /** 底层物理网络观察者：负责 setUnderlyingNetworks（分流 VPN 必须，Bedlam 同款） */
    private var networkObserver: UnderlyingNetworkObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 同步用户设置到进程内缓存（防 START_STICKY 冷启动 / 无 UI 场景用默认模式连接）
        runCatching {
            kotlinx.coroutines.runBlocking {
                val s = LumiApp.instance.settings
                ProxyModeHolder.current = s.proxyMode.first()
                RulePolicyHolder.cnDirect = s.chinaDirect.first()
                RulePolicyHolder.abroadProxy = s.abroadProxy.first()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_CONNECT
        lastStartId = startId
        when (action) {
            ACTION_DISCONNECT -> {
                stop(DisconnectReason.USER)
                return START_NOT_STICKY
            }
            else -> {
                if (!running) {
                    running = true
                    stopRequested = false
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startForeground(
                                NOTIFY_ID, buildNotification("Lumi", "连接中…"),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                        } else {
                            startForeground(NOTIFY_ID, buildNotification("Lumi", "连接中…"))
                        }
                    } catch (e: Exception) {
                        log("W", "前台通知失败（不影响 VPN）: ${e.message}")
                    }
                    worker = scope.launch { runVpn() }
                    startNetworkObserver()
                }
                return START_STICKY
            }
        }
    }

    // ------------------------------------------------------------------
    // 主流程：官方 golib 核心（Bedlam 同源）
    // ------------------------------------------------------------------

    private suspend fun runVpn() {
        val client = LumiVpnService.client
        try {
            // 1. 读取配置
            val node = LumiApp.instance.repository.getSelected()
            if (node == null) {
                log("E", "未选择节点，请先在首页添加并选择配置")
                failAndStop("未选择节点，请先在首页添加并选择配置")
                return
            }
            currentNode = node

            // 2. 域名预解析（根治重连 DNS 警告）：
            //    连接前用【系统 DNS】把服务器域名解析成 IP，生成 URI 时 host=IP、SNI=原域名。
            //    核心此后只连 IP，不再自行解析域名——避免断线重连时 TUN 内 DNS(1.1.1.1，
            //    国内不可达) 解析失败导致的 dial backoff 警告与 attempt 反复重连。
            //    已填 IP 的配置原样直通；解析失败则回退原域名（保持旧行为）。
            val connectNode = runCatching {
                val host = node.server.trim()
                val looksLikeIp = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || host.contains(":")
                if (host.isEmpty() || looksLikeIp) {
                    node
                } else {
                    // 取全部解析结果，优先 IPv4：避免系统返回 IPv6 首选导致连接黑洞超时
                    val addrs = java.net.InetAddress.getAllByName(host)
                    val ipv4 = addrs.firstOrNull { it is java.net.Inet4Address }
                    val pick = ipv4 ?: addrs.firstOrNull()
                    val addr = pick?.hostAddress ?: host
                    val ipHost = if (addr.contains(":")) "[$addr]" else addr
                    // SNI 安全纠偏：关闭"允许不安全"时，TLS 会严格校验 SNI 与服务器证书匹配。
                    // 若配置里填了与服务器域名不一致的伪装 SNI（如 www.cloudflare.com），
                    // 证书校验必然失败（CRYPTO_ERROR 0x12a）。此时自动改用服务器域名做 SNI
                    // ——证书校验仍开启（不降级安全），自有合法证书即可正常连接。
                    // 服务器填 IP 的场景不纠偏（SNI 由用户自行指定，跳过此逻辑）。
                    val safeSni = when {
                        node.sni.isBlank() -> host
                        !node.insecure && !node.sni.equals(host, true) -> host
                        else -> node.sni
                    }
                    node.copy(server = ipHost, sni = safeSni)
                }
            }.getOrDefault(node)

            // 3. 节点 -> hysteria2 URI -> 官方配置对象（host=IP，sni=域名）
            val uri = runCatching { Hysteria2UriParser.toUri(connectNode) }.getOrElse {
                log("E", "生成配置链接失败: ${it.message}")
                failAndStop("配置错误: ${it.message}")
                return
            }
            val parsed = try {
                parseHysteriaUri(uri)
            } catch (e: Exception) {
                log("E", "配置解析失败: ${e.message}")
                failAndStop("配置解析失败: ${e.message}")
                return
            }
            val config = parsed.config
            log("I", "正在连接 ${node.server}:${node.port}")

            // 4. 官方核心：握手成功 -> 回调建立 TUN -> 完成接管（Bedlam 同款）
            client.start(
                config = config,
                tunConfig = tunConfig,
                protector = { fd -> protect(fd) },
                tun = { buildTunPfd() }
            )
            log("I", "连接成功")
            VpnController.onSessionStart(node)
            VpnController.onServiceStarted(-1)
            updateNotification(node.displayName(), "已连接成功")

            // 4. 观察状态 / 统计 / 日志
            watch(client, node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("E", "连接失败: ${e.message ?: e.toString()}")
            val st = client.state.value
            val detail = (st as? ConnectionState.Error)?.message
            val reason = detail ?: e.message
            // 证书校验失败 → 输出一条可读诊断（替代难懂的 x509 原文）：
            // 服务器填域名时已被上面 SNI 纠偏覆盖；走到这里通常是服务器填 IP + 伪装 SNI，
            // 或证书本身不受信（过期/自签/中间人）。
            if (reason?.contains("verify certificate") == true || reason?.contains("0x12a") == true) {
                runCatching {
                    LumiApp.instance.repository.addLog(
                        "E",
                        if (currentNode?.insecure == true)
                            "证书校验失败：节点已开启跳过校验仍报错，请检查服务器地址/SNI 是否可达"
                        else
                            "证书校验失败：节点 SNI 与服务器证书不匹配。若服务器填的是 IP，" +
                                "请在节点编辑里把 SNI 改为你的真实域名；他人节点请开启\"允许不安全\""
                    )
                }
            }
            flushCoreError(reason)
            failAndStop(
                if (detail != null) "hysteria2 连接失败\n原因: $detail"
                else "hysteria2 连接失败: ${e.message}"
            )
        }
    }

    /** 将核心错误（若有）写进日志库 */
    private suspend fun flushCoreError(detail: String?) {
        if (!detail.isNullOrBlank()) {
            runCatching { LumiApp.instance.repository.addLog("E", detail) }
        }
    }

    private fun watch(client: HysteriaClient, node: NodeConfig) {
        // 状态监听：核心状态变化 -> 同步 UI（Bedlam ReconnectWatchdog 对齐）
        // 先取消旧收集器：避免多次连接累积重复订阅（日志重复/状态重复处理）
        stateJob?.cancel()
        stateJob = scope.launch {
            client.state.collect { st ->
                when (st) {
                    is ConnectionState.Connected -> {
                        VpnController.onServiceStarted(-1)
                        updateNotification(node.displayName(), "已连接")
                    }
                    is ConnectionState.Error -> {
                        log("E", "连接错误: ${st.message}")
                        if (running && !stopRequested) {
                            VpnController.onError(st.message)
                            stop(DisconnectReason.USER)
                        }
                    }
                    is ConnectionState.Reconnecting -> {
                        log("W", "连接中断，重连中…(${st.attempt}) ${st.reason}")
                    }
                    is ConnectionState.Disconnected -> {
                        if (running && !stopRequested && st.reason != DisconnectReason.USER) {
                            log("W", "连接已断开: ${st.reason}")
                            stop(DisconnectReason.USER)
                        }
                    }
                    else -> {}
                }
            }
        }
        // 日志流 -> Room（核心日志）：同样先取消旧收集器，防重复写入
        logJob?.cancel()
        logJob = scope.launch {
            runCatching {
                client.logs().collect { entry ->
                    runCatching {
                        LumiApp.instance.repository.addLog(
                            when (entry.level.name) {
                                "ERROR" -> "E"
                                "WARN" -> "W"
                                else -> "I"
                            },
                            entry.message
                        )
                    }
                }
            }
        }
        // 统计轮询：0.1s 采样（动态曲线丝滑推进）+ 通知节流 1s + 会话流量上报 + 模式热切换
        statsJob?.cancel()
        statsJob = scope.launch {
            var prevDown = 0L
            var prevUp = 0L
            var prevTs = 0L
            var lastNotifyTs = 0L
            var lastMode = ProxyModeHolder.current
            var lastCn = RulePolicyHolder.cnDirect
            var lastAb = RulePolicyHolder.abroadProxy
            while (scope.isActive && running && !stopRequested) {
                delay(POLL_INTERVAL_MS)
                if (!running || stopRequested) break
                try {
                    // 模式/规则开关热切换：连接中改动 -> updateTun 重建 TUN（QUIC 不断线）
                    val modeNow = ProxyModeHolder.current
                    val cnNow = RulePolicyHolder.cnDirect
                    val abNow = RulePolicyHolder.abroadProxy
                    if (modeNow != lastMode || cnNow != lastCn || abNow != lastAb) {
                        lastMode = modeNow
                        lastCn = cnNow
                        lastAb = abNow
                        log("I", "检测到代理模式变化 -> 重建 TUN（${modeName(modeNow)} cn=$cnNow ab=$abNow）")
                        try {
                            client.updateTun(tunConfig) { buildTunPfd() }
                            log("I", "TUN 已按新模式重建")
                        } catch (e: Exception) {
                            log("W", "TUN 重建失败: ${e.message}")
                        }
                    }
                    val st = client.stats()
                    if (st != null) {
                        VpnController.lastUpBytes = st.txBytes
                        VpnController.lastDownBytes = st.rxBytes
                        VpnController.activeConns = 1
                        // 会话总流量（下行+上行）实时上报（供首页双累计）
                        VpnController.reportSessionBytes(st.txBytes + st.rxBytes)
                        // 通知栏实时速度（1s 节流，避免高频刷新耗电）
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyTs >= 1000L) {
                            lastNotifyTs = now
                            if (prevTs > 0) {
                                val dt = ((now - prevTs).coerceAtLeast(1)) / 1000f
                                val downSpeed = (st.rxBytes - prevDown).coerceAtLeast(0) / dt
                                val upSpeed = (st.txBytes - prevUp).coerceAtLeast(0) / dt
                                updateNotification(
                                    node.displayName(),
                                    "上传 ${fmtSpeed(upSpeed)}    下载 ${fmtSpeed(downSpeed)}"
                                )
                            }
                        }
                        prevDown = st.rxBytes
                        prevUp = st.txBytes
                        prevTs = now
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun fmtSpeed(bps: Float): String {
        val kb = bps / 1024f
        val mb = kb / 1024f
        return when {
            mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB/s", mb)
            kb >= 1 -> String.format(java.util.Locale.US, "%.1f KB/s", kb)
            else -> String.format(java.util.Locale.US, "%.0f B/s", bps)
        }
    }

    /**
     * 建立 Android TUN（由官方核心在握手成功后回调调用）
     *  地址段 10.66.66.1：避免与 Clash 等客户端默认 172.19.0.1/30 冲突
     *  路由按当前代理模式构建（Bedlam RoutePlan 机制）：
     *   全局 = claim 0.0.0.0/0（全量进 VPN）
     *   规则 = claim 0.0.0.0/0 + exclude 中国段（国内直连，国外代理）
     *   直连 = 不 claim 任何外部路由（流量全走原网络）
     *  API 33+ 用 excludeRoute；低版本 fallback：规则模式=全局（提示）
     */
    private fun buildTunPfd(): ParcelFileDescriptor {
        val node = currentNode
        val mode = ProxyModeHolder.current
        val builder = Builder()
            .setSession(node?.displayName() ?: "Lumi")
            .setMtu(1280)
            .setMetered(false)
            .addAddress("10.66.66.1", 32)
        when (mode) {
            ProxyMode.GLOBAL -> {
                builder.addRoute("0.0.0.0", 0)
                builder.addDnsServer("8.8.8.8")
            }
            ProxyMode.RULE -> {
                // 规则分流（固定开启，UI 无附属开关）：国外流量走代理 + 中国段直连
                builder.addRoute("0.0.0.0", 0)
                builder.addDnsServer("8.8.8.8")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // 中国段直连（不进 VPN）——Bedlam 量级：精简大段优先，
                    // 避免一次性排除数千条导致系统不把 VPN 视为活动（图标消失）
                    val cn = ChinaCidr.loadCompact(this)
                    cn.forEach { runCatching { builder.excludeRoute(it) } }
                }
                // API <33 无 excludeRoute：规则模式退化为全局（低版本设备提示由上层负责）
            }
            else -> {
                // DIRECT：全直连 —— 只保留自身地址，不 claim 外部路由
            }
        }
        val pfd = builder.establish()
            ?: throw IllegalStateException("VpnService.establish() returned null")
        // 分流 VPN 必须声明 underlying networks，否则系统不视为活动 VPN（图标消失）。
        // observer 回调可能早于 establish（被系统忽略），这里建立后补报一次。
        runCatching {
            val underlying = networkObserver?.current()
            if (underlying != null) {
                setUnderlyingNetworks(arrayOf(underlying))
            }
        }
        log("I", "TUN 已建立（模式: ${modeName(mode)}）")
        return pfd
    }

    private fun modeName(mode: Int): String = when (mode) {
        ProxyMode.GLOBAL -> "全局"
        ProxyMode.RULE -> "规则"
        else -> "直连"
    }

    /** 启动底层网络观察者：VPN 分流（excluded routes）必须声明 underlying networks，
     * 否则系统不把 VPN 视为活动 VPN（状态栏图标消失）。Bedlam 同款机制。 */
    private fun startNetworkObserver() {
        if (networkObserver != null) return
        networkObserver = UnderlyingNetworkObserver(
            context = this,
            scope = scope,
            onAvailable = { network ->
                runCatching {
                    setUnderlyingNetworks(network?.let { arrayOf(it) })
                }.onFailure { e ->
                    log("W", "setUnderlyingNetworks 失败: ${e.message}")
                }
            },
            onSettledChange = {
                log("I", "底层网络已切换稳定")
            },
        ).also { it.start() }
        log("I", "底层网络观察已启动")
    }

    private fun stopNetworkObserver() {
        networkObserver?.stop()
        networkObserver = null
    }

    private fun failAndStop(reason: String) {
        VpnController.onError(reason)
        stop(DisconnectReason.USER)
    }

    /** 统一停止流程（与 Bedlam stop(reason) 对齐）：
     * 复位 UI -> suspend client.stop() 等待核心/TUN 完全释放 -> stopSelf。
     * TUN fd 必须等 golib 释放干净，其他客户端接管后路由才无残留冲突。 */
    @Synchronized
    private fun stop(reason: DisconnectReason = DisconnectReason.USER) {
        if (!running && stopRequested) return
        running = false
        stopRequested = true
        worker?.cancel()
        stateJob?.cancel()
        logJob?.cancel()
        statsJob?.cancel()
        stopNetworkObserver()
        // 立即复位 UI 状态
        VpnController.onServiceStopped()
        // 核心清理：suspend stop() 等待真正释放完成后才停服务
        scope.launch {
            try {
                LumiVpnService.client.stop(reason)
            } catch (_: Exception) {
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(lastStartId)
        }
    }

    override fun onDestroy() {
        log("W", "onDestroy 触发")
        stopNetworkObserver()
        if (running || !stopRequested) {
            running = false
            stopRequested = true
            worker?.cancel()
            stateJob?.cancel()
            logJob?.cancel()
            statsJob?.cancel()
            VpnController.onServiceStopped()
        }
        // 兜底：无论如何释放核心
        runCatching { LumiVpnService.client.shutdown(DisconnectReason.USER) }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onRevoke() {
        log("W", "onRevoke 触发（系统撤销 VPN 授权）")
        stop(DisconnectReason.REVOKED)
    }

    /**
     * 自定义状态日志已停用：日志页只展示核心引擎（golib）的原始输出，
     * 不再混入 App 旁白（连接状态/排除条数等）。保留调用点便于排查时临时开启。
     */
    private fun log(level: String, msg: String) {
        // 停用：不写入日志库
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "VPN 连接状态",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, LumiVpnService::class.java).putExtra(EXTRA_ACTION, ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, "断开", stopPi)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFY_ID, buildNotification(title, content))
        }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        private const val CHANNEL_ID = "lumi_vpn"
        private const val NOTIFY_ID = 1
        private const val POLL_INTERVAL_MS = 100L

        /** 官方 golib 核心客户端（Bedlam 同源），进程级单例 */
        val client: HysteriaClient by lazy { HysteriaClientImpl() }

        /**
         * TUN 统一配置（连接与模式热重建共用同一份，保证 DNS 上游一致）：
         * DNS 上游默认 TCP(1.1.1.1) 在国内隧道出口偶发握手超时，
         * 改为 UDP + Cloudflare/Google 多冗余——查询经隧道由服务器出口转发，
         * UDP 无握手延迟，失败自动换下一台，性能与稳定性更优。
         */
        val tunConfig: TunConfig = TunConfig(
            dns = DnsUpstream(
                transport = DnsTransport.Udp,
                servers = listOf("1.1.1.1:53", "1.0.0.1:53", "8.8.8.8:53", "8.8.4.4:53")
            )
        )
    }
}

/** 代理模式进程内缓存（兼容旧引用） */
object ProxyModeHolder {
    @Volatile
    var current: Int = ProxyMode.RULE
}

/** 规则代理微调缓存（兼容旧引用） */
object RulePolicyHolder {
    @Volatile
    var cnDirect: Boolean = true

    @Volatile
    var abroadProxy: Boolean = true
}