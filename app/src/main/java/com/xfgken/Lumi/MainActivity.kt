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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.graphics.Color
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

    /** 电源无限制（忽略电池优化）——系统实时检测 */
    private var batteryDone by mutableStateOf(false)

    /** 自启动——系统无检测 API，以“已跳转系统自启动管理页”为完成标记 */
    private var autoStartDone by mutableStateOf(false)

    private val permPrefs by lazy { getSharedPreferences("lumi_perms", MODE_PRIVATE) }

    /** 是否已忽略电池优化（系统可查，实时准确） */
    private fun batteryIgnored(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

    /** 自启动无标准检测 API：以"已跳转系统自启动管理页引导过"作为完成标记 */
    private fun autoStartGuided(): Boolean =
        permPrefs.getBoolean("autostart_tapped", false)

    /** 刷新两项状态（不改变弹窗显隐） */
    private fun updatePermStates() {
        batteryDone = batteryIgnored()
        autoStartDone = autoStartGuided()
    }

    /** 冷启动初始判定：任一未开启 → 弹引导窗（此后不再自动关闭，由用户点按钮关闭） */
    private fun refreshGuide() {
        updatePermStates()
        showGuide = !batteryDone || !autoStartDone
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
        // 仅更新状态为已完成（弹窗保持显示，等用户看到双绿勾后点“完成”关闭）
        autoStartDone = true
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

        // 冷启动权限检测：任一未开启 → 弹引导窗
        refreshGuide()

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
                // 权限引导弹窗：两项均开启后不再出现；开启状态实时检测并显示绿勾
                if (showGuide) {
                    // 弹窗显示期间持续轮询（400ms）：从系统设置开启返回后绿勾快速出现，
                    // 不依赖 onResume 时序
                    LaunchedEffect(showGuide) {
                        while (showGuide) {
                            updatePermStates()
                            kotlinx.coroutines.delay(400)
                        }
                    }
                    PermissionGuideDialog(
                        batteryOk = batteryDone,
                        autoStartOk = autoStartDone,
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
        // 回到前台：立即刷新两项状态（不自动关闭弹窗，由用户点按钮决定）；
        // 弹窗显示期间另有轮询兜底，保证绿勾快速出现
        updatePermStates()
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
// 权限引导弹窗：忽略电池优化（电源无限制）+ 自启动，开启后显示绿勾；全开则「完成」
// ---------------------------------------------------------------------------
/** 成功绿（与连接成功同款） */
private val GuideOkColor = Color(0xFF00C853)

@Composable
private fun PermissionGuideDialog(
    batteryOk: Boolean,
    autoStartOk: Boolean,
    onOpenBattery: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val allDone = batteryOk && autoStartOk
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
                    done = batteryOk,
                    onClick = onOpenBattery
                )
                GuideItem(
                    icon = Icons.Outlined.Bolt,
                    title = "自启动管理",
                    subtitle = "允许开机自启与后台运行，服务随时可用",
                    done = autoStartOk,
                    onClick = onOpenAutoStart
                )
                Text(
                    "开启后返回本页，检测到已开启会显示绿色对勾。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    if (allDone) "完成" else "暂不开启",
                    color = if (allDone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (allDone) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    )
}

/** 权限项卡片：图标 + 标题/说明 + 右侧（未开启=去开启按钮，已开启=绿色对勾） */
@Composable
private fun GuideItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    done: Boolean,
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
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        if (done) GuideOkColor.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = if (done) GuideOkColor else MaterialTheme.colorScheme.primary,
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
            if (done) {
                // 已开启：绿色对勾 + 已开启
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = GuideOkColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "已开启",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GuideOkColor
                    )
                }
            } else {
                TextButton(onClick = onClick) {
                    Text(
                        "去开启",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}