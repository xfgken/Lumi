package com.xfgken.Lumi

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    // ---- 权限引导（电源无限制 + 自启动） ----
    /** 引导弹窗可见性（随生命周期刷新：外部开启后返回自动关闭/隐藏对应项） */
    private var showGuide by mutableStateOf(false)

    private val permPrefs by lazy { getSharedPreferences("lumi_perms", MODE_PRIVATE) }

    /** 是否已忽略电池优化（系统可查，实时准确） */
    private fun batteryIgnored(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

    /** 自启动无标准检测 API：以"已跳转系统自启动管理页引导过"作为完成标记 */
    private fun autoStartGuided(): Boolean =
        permPrefs.getBoolean("autostart_tapped", false)

    private fun refreshGuide() {
        showGuide = !batteryIgnored() || !autoStartGuided()
    }

    /** 跳转系统「忽略电池优化」请求（电源无限制使用） */
    private fun openBatterySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    /** 跳转系统「自启动」管理页（小米 MIUI/HyperOS 专用，其余机型回退应用详情页） */
    private fun openAutoStartSettings() {
        permPrefs.edit().putBoolean("autostart_tapped", true).apply()
        refreshGuide()
        // 1) 小米 MIUI / HyperOS 自启动管理
        try {
            startActivity(
                Intent("miui.intent.action.OP_AUTO_START").apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        } catch (_: Exception) { /* 非 MIUI，走兜底 */ }
        // 2) 通用兜底：应用详情页（用户在其中开启自启动/后台运行）
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Exception) { /* 忽略 */ }
    }

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
                // 权限引导弹窗：两项均完成后不再出现（电源无限制可实时检测，
                // 自启动以引导过为完成标记）
                if (showGuide) {
                    PermissionGuideDialog(
                        batteryOk = batteryIgnored(),
                        onOpenBattery = ::openBatterySettings,
                        onOpenAutoStart = ::openAutoStartSettings,
                        onDismiss = { showGuide = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台都复查：在系统设置里开启后返回，弹窗自动消失/更新
        refreshGuide()
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

// ---------------------------------------------------------------------------
// 权限引导弹窗：忽略电池优化（电源无限制）+ 自启动，两项各自跳系统设置
// ---------------------------------------------------------------------------
@Composable
private fun PermissionGuideDialog(
    batteryOk: Boolean,
    onOpenBattery: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "开启此权限，让你的服务更加稳定",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GuideItem(
                    icon = Icons.Outlined.BatteryChargingFull,
                    title = "电源无限制使用",
                    subtitle = "忽略电池优化，防止休眠时自动断开连接",
                    actionLabel = if (batteryOk) "已开启" else "去开启",
                    enabled = !batteryOk,
                    onClick = onOpenBattery
                )
                GuideItem(
                    icon = Icons.Outlined.Bolt,
                    title = "自启动",
                    subtitle = "允许开机自启与后台运行，服务随时可用",
                    actionLabel = "去开启",
                    enabled = true,
                    onClick = onOpenAutoStart
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("暂不开启", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/** 权限项卡片：图标 + 标题/说明 + 右侧状态按钮 */
@Composable
private fun GuideItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onClick,
                enabled = enabled
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (!enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}