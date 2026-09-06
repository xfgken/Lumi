package com.xfgken.Lumi

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.xfgken.Lumi.data.NodeRepository
import com.xfgken.Lumi.data.SettingsStore
import com.xfgken.Lumi.utils.CrashCatcher
import com.xfgken.Lumi.vpn.QuickConnectTileService
import com.xfgken.Lumi.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lumi Application：全局单例 + 崩溃捕获 + 下拉磁贴状态同步。
 */
class LumiApp : Application() {
    lateinit var repository: NodeRepository
        private set

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = NodeRepository(this)
        settings = SettingsStore(this)
        // 崩溃捕获：上次崩溃堆栈写入日志页，便于排查
        CrashCatcher.install(this)
        CrashCatcher.drain(this)?.let { crash ->
            CoroutineScope(Dispatchers.IO).launch {
                repository.addLog("E", crash)
            }
        }
        // 下拉控制栏磁贴：VPN 状态一变就请求系统刷新磁贴（连接/断开文字与点亮状态实时同步）
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            VpnController.state.collect {
                runCatching {
                    TileService.requestListeningState(
                        this@LumiApp,
                        ComponentName(this@LumiApp, QuickConnectTileService::class.java)
                    )
                }
            }
        }
    }

    companion object {
        lateinit var instance: LumiApp
            private set

        fun context(): Context = instance
    }
}