package com.xfgken.Lumi.vpn

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.xfgken.Lumi.LumiApp
import com.xfgken.Lumi.MainActivity
import com.xfgken.Lumi.R
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
 *  - 已连接 / 连接中 → 断开（Toast「Lumi服务已关闭」）；
 *  - 未连接 → 校验配置节点后发起连接（Toast「Lumi服务已启动」）；
 *    未授权 VPN / 无任何配置 → 自动打开 App 引导（授权框 / 添加配置）。
 * 磁贴显示：关闭态 = 电源图标 +「Lumi服务已关闭」；开启态 = L 字样图标 +「Lumi服务已启动」。
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
            VpnState.CONNECTED, VpnState.CONNECTING -> {
                VpnController.disconnect(applicationContext)
                toast("Lumi服务已关闭")
            }
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
                toast("请先在 Lumi 中添加配置")
                openApp()
                return@launch
            }
            // 未授权 VPN：connect() 会置 CONNECTING 并发出授权 Intent，
            // 打开 MainActivity 由其消费并弹出系统授权框，授权成功自动续接连接
            val needAuth = VpnService.prepare(app) != null
            val started = VpnController.connect(app)
            if (!needAuth && started) {
                toast("Lumi服务已启动")
            } else if (needAuth) {
                openApp()
            } else {
                toast("启动失败，请打开 Lumi 查看日志")
            }
            refreshTile()
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

    private fun toast(msg: String) {
        runCatching { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 磁贴显示：
     *  关闭态 = 电源图标（灰，点击启动）+ 「Lumi服务已关闭」
     *  开启态 = L 字样图标（点亮）+ 「Lumi服务已启动」
     */
    private fun refreshTile() {
        val tile = qsTile ?: return
        val st = VpnController.state.value
        val active = st == VpnState.CONNECTED || st == VpnState.CONNECTING
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (active) R.drawable.ic_tile_l else R.drawable.ic_tile_power
        )
        val label = if (active) "Lumi服务已启动" else "Lumi服务已关闭"
        tile.label = label
        tile.contentDescription = label
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