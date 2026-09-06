package com.xfgken.Lumi

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfgken.Lumi.ui.HomeScreen
import com.xfgken.Lumi.ui.theme.LumiTheme
import com.xfgken.Lumi.viewmodel.MainViewModel
import com.xfgken.Lumi.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                vm.onVpnPrepared()
            } else {
                VpnController.forceReset()
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ 通知权限（前台服务通知）
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)

        setContent {
            val settings by vm.settingsUi.collectAsStateWithLifecycle()
            // 首页滚动状态提升到主题过渡之外：切主题不丢滚动位置（不跳回顶部）
            val homeListState = rememberLazyListState()
            // 消费 VPN 授权 Intent（Activity 上下文启动，避免闪退）
            LaunchedEffect(Unit) {
                vm.prepareIntent.collect { intent ->
                    if (intent != null) {
                        vpnPermission.launch(intent)
                        vm.consumePrepareIntent()
                    }
                }
            }
            // 主题/配色：单树即时切换（不重建页面树；颜色同帧重组，
            // 无 Crossfade 双树交叉 → 消除深/浅切换白屏闪烁）
            // themeMode 0=跟随系统 仅兼容旧数据，按浅色处理（UI 只有深/浅两态）
            LumiTheme(
                mode = if (settings.themeMode == 0) 1 else settings.themeMode
            ) {
                // 单页应用：无底部栏；日志/外观/关于都在首页右上角
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .systemBarsPadding()
                ) {
                    HomeScreen(vm, listState = homeListState)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 处理 hysteria2:// 链接导入 */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.dataString ?: return
        if (!uri.startsWith("hysteria2://") && !uri.startsWith("hy2://")) return
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching {
                LumiApp.instance.repository.importUri(uri)
                true
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "配置已导入，请在首页选择" else "导入失败：链接格式无效",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}