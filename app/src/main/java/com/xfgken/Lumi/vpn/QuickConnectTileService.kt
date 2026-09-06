package com.xfgken.Lumi.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xfgken.Lumi.LumiApp
import com.xfgken.Lumi.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下拉控制栏磁贴（Quick Settings Tile）——Clash 同款快捷连接按钮。
 *
 * 点击行为：
 *  - 已连接 / 连接中 → 断开；
 *  - 未连接 → 校验配置节点后发起连接；
 *    未授权 VPN / 无任何配置 → 自动打开 App 引导（授权框 / 添加配置）。
 * 磁贴文字随状态切换为「连接 / 断开」，LumiApp 中全局监听状态变化实时刷新。
 */
class QuickConnectTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴可见（面板展开）时同步一次状态
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        when (VpnController.state.value) {
            VpnState.CONNECTED, VpnState.CONNECTING ->
                VpnController.disconnect(applicationContext)
            // 正在断开：忽略本次点击（避免与旧 stop 并发操作同一核心）
            VpnState.DISCONNECTING -> Unit
            else -> tryConnect()
        }
        refreshTile()
    }

    private fun tryConnect() {
        val app = applicationContext
        scope?.launch {
            // 无任何配置：打开 App 引导添加（不进入连接流程）
            val hasNode = withContext(Dispatchers.IO) {
                runCatching { LumiApp.instance.repository.getAll().isNotEmpty() }
                    .getOrDefault(false)
            }
            if (!hasNode) {
                openApp()
                return@launch
            }
            // 未授权 VPN：connect() 会置 CONNECTING 并发出授权 Intent，
            // 打开 MainActivity 由其消费并弹出系统授权框，授权成功自动续接连接
            val needAuth = VpnService.prepare(app) != null
            if (needAuth) {
                VpnController.connect(app)
                openApp()
            } else {
                VpnController.connect(app)
                refreshTile()
            }
        }
    }

    /** 打开 App（折叠面板），供授权 / 引导添加配置使用 */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivityAndCollapse(intent)
            } else {
                startActivity(intent)
            }
        }
    }

    /** 磁贴显示：连接中（含 CONNECTING）= 点亮 + 「断开」，否则灰色 + 「连接」 */
    private fun refreshTile() {
        val tile = qsTile ?: return
        val st = VpnController.state.value
        val active = st == VpnState.CONNECTED || st == VpnState.CONNECTING
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "断开" else "连接"
        tile.contentDescription =
            if (active) "Lumi 已连接，点击断开" else "Lumi 未连接，点击连接"
        tile.updateTile()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        scope?.cancel()
        scope = null
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}