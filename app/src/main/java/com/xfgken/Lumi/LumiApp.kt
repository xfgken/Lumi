package com.xfgken.Lumi

import android.app.Application
import android.content.Context
import com.xfgken.Lumi.data.NodeRepository
import com.xfgken.Lumi.data.SettingsStore
import com.xfgken.Lumi.utils.CrashCatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lumi Application：全局单例 + 崩溃捕获。
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
    }

    companion object {
        lateinit var instance: LumiApp
            private set

        fun context(): Context = instance
    }
}