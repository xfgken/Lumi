package com.xfgken.Lumi.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfgken.Lumi.model.NodeConfig
import com.xfgken.Lumi.viewmodel.MainViewModel
import com.xfgken.Lumi.viewmodel.SpeedSample
import com.xfgken.Lumi.viewmodel.VpnStats
import com.xfgken.Lumi.vpn.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页（干净版）：
 * 顶部 Lumi → Bedlam 风格连接按钮（连接成功后按钮内显示时长）→
 * 实时网速/累计流量 → 我的配置（仅名称 + 延迟 + 刷新 + 菜单）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    val context = LocalContext.current
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val vpnState by vm.vpnState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val seconds by vm.connectedSeconds.collectAsStateWithLifecycle()
    val errorMsg by vm.errorMessage.collectAsStateWithLifecycle()
    val settings by vm.settingsUi.collectAsStateWithLifecycle()

    var showSheet by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<NodeConfig?>(null) }
    var showNoConfig by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    // 跳过部分展开：内容 72% 高度，打开动画直接丝滑停在约 70%，不再默认 50% 需手动上拉
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()

    val selected = nodes.firstOrNull { it.isSelected }

    // 深浅主题（右上角图标按钮切换）
    val isDarkTheme = settings.themeMode == 2
    // 主题动画窗口（500ms ≥ 全局 420ms 色板渐变）：期间胶囊/配置列表/连接按钮等
    // 组件颜色动画直接跟随主题当前色（snap），消除切换深浅时"慢半拍"的双重滤波；
    // 窗口结束后恢复状态切换动画（选中/连接/滑块过渡）
    var themeSnap by remember { mutableStateOf(true) }
    LaunchedEffect(isDarkTheme) {
        themeSnap = true
        delay(500)
        themeSnap = false
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ============ 顶部：Lumi（点击打开运行日志）+ 主题/关于 ============
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lumi",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showLogs = true }
                    )
                )
                Spacer(Modifier.weight(1f))
                // 深/浅色一键切换（图标按钮，非胶囊）
                TopIconButton(
                    icon = if (isDarkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    desc = if (isDarkTheme) "切换到浅色" else "切换到深色",
                    onClick = { vm.toggleTheme() }
                )
                Spacer(Modifier.width(6.dp))
                // 关于（信息弹窗）
                TopIconButton(
                    icon = Icons.Outlined.Info,
                    desc = "关于",
                    onClick = { showAbout = true }
                )
            }
        }

        // ============ 网速 / 本次流量 ============
        item {
            val history by vm.speedHistory.collectAsStateWithLifecycle()
            SpeedCard(stats, history)
        }

        // ============ 代理模式（胶囊：全局/规则/直连，规则附属开关固定开启） ============
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "代理模式",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            when (settings.proxyMode) {
                                com.xfgken.Lumi.vpn.ProxyMode.GLOBAL -> "所有流量走代理"
                                com.xfgken.Lumi.vpn.ProxyMode.RULE -> "国内直连 · 国外代理"
                                else -> "所有流量直连"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PillSelector(
                        options = listOf("全局", "规则", "直连"),
                        selectedIndex = settings.proxyMode,
                        onSelect = { vm.setProxyMode(it) },
                        snapColors = themeSnap
                    )
                }
            }
        }

        // ============ 我的配置 ============
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "我的配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (nodes.isNotEmpty()) {
                    TextButton(onClick = {
                        editingNode = null
                        showSheet = true
                    }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("添加", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (nodes.isEmpty()) {
            item {
                EmptyCard {
                    editingNode = null
                    showSheet = true
                }
            }
        } else {
            item {
                // 配置外框卡片：内含每个配置独立小卡（带间距），点击实时切换连接
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp
                ) {
                    Column(
                        Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        nodes.forEach { node ->
                            ProfileRow(
                                node = node,
                                isSelected = node.id == selected?.id,
                                snapColors = themeSnap,
                                onClick = {
                                    if (node.id != selected?.id) {
                                        // 实时切换：连接中也会自动断开并重连（无 Toast）
                                        vm.onNodeClick(node)
                                    } else {
                                        // 点击已选中配置 = 编辑
                                        editingNode = node
                                        showSheet = true
                                    }
                                },
                                onDelete = { vm.deleteNode(node) },
                                onPing = { done -> vm.pingNode(node) { _ -> done() } },
                                onCopyLink = {
                                    runCatching {
                                        val uri = com.xfgken.Lumi.core.Hysteria2UriParser.toUri(node)
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText("Lumi 配置", uri)
                                        )
                                        Toast.makeText(context, "已复制订阅链接", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ============ 右下角悬浮连接按钮（Bedlam 形变） ============
    ConnectionFab(
        state = vpnState,
        durationSeconds = seconds,
        snapColors = themeSnap,
        onClick = {
            if (nodes.isEmpty()) {
                showNoConfig = true
            } else {
                vm.onConnectClick()
            }
        },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 18.dp)
    )

    // ============ 无配置提示弹窗（小、红色元素、确定） ============
    if (showNoConfig) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNoConfig = false },
            icon = {
                Icon(
                    Icons.Filled.Warning, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            },
            title = { Text("未添加配置") },
            confirmButton = {
                TextButton(onClick = { showNoConfig = false }) { Text("确定") }
            }
        )
    }

    // ============ 顶部错误提示浮层（动画弹出，不占布局不闪烁） ============
    AnimatedVisibility(
        visible = errorMsg != null,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(tween(200)) + expandVertically(tween(220)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(200))
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)
        ) {
            Text(
                errorMsg ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
    }

    // ============ 关于弹窗（右上角 ⓘ） ============
    if (showAbout) {
        AboutDialog(coreLoaded = settings.coreLoaded, onDismiss = { showAbout = false })
    }

    // 错误横幅 6 秒后自动消失（避免失败原因常驻顶部）
    LaunchedEffect(errorMsg) {
        if (errorMsg != null) {
            kotlinx.coroutines.delay(6000)
            vm.clearError()
        }
    }

    // ============ 日志大窗口（右上角 📄：左右近全宽、高约 80%、贴屏幕下方） ============
    if (showLogs) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showLogs = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // 全屏透明容器：上方留空为遮罩（点击空白关闭），下方为日志窗口
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showLogs = false }
                    )
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.8f)
                        .padding(start = 6.dp, end = 6.dp, bottom = 10.dp)
                        // 消费窗口内部点击（列表滚动/复制等），避免冒泡触发关闭
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // 内容淡入：窗口弹出后小字内容丝滑浮现
                        val contentAlpha = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { contentAlpha.animateTo(1f, tween(240)) }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .alpha(contentAlpha.value)
                        ) {
                            LogScreen(vm, onClose = { showLogs = false })
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                sheetScope.launch {
                    sheetState.hide()
                    showSheet = false
                    editingNode = null
                }
            },
            sheetState = sheetState
        ) {
            NodeEditorSheet(
                initial = editingNode,
                onSave = { cfg ->
                    if (editingNode == null) {
                        vm.addNode(cfg)
                    } else {
                        vm.updateNode(cfg)
                        // 编辑的是正在连接/连接中的选中节点 → 保存后自动重连使新配置生效
                        val wasActive = editingNode?.isSelected == true &&
                            (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING)
                        if (wasActive) vm.reconnectAfterEdit()
                    }
                    sheetScope.launch {
                        sheetState.hide()
                        showSheet = false
                        editingNode = null
                    }
                },
                onCancel = {
                    sheetScope.launch {
                        sheetState.hide()
                        showSheet = false
                        editingNode = null
                    }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 右下角连接按钮：Bedlam 同款 Morph 形变（Material3 Expressive）
// 未连接：方形 + 电源图标 ｜ 连接中：按钮在多边形间循环形变（加载动画）
// 已连接：方形 + 成功绿对勾 ｜ 颜色全部来自主题/状态语义色
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionFab(
    state: VpnState,
    onClick: () -> Unit,
    durationSeconds: Long = 0L,
    snapColors: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isConnecting = state == VpnState.CONNECTING || state == VpnState.DISCONNECTING
    val isConnected = state == VpnState.CONNECTED

    // —— Bedlam 风格形状形变 ——
    val restingShape = MaterialShapes.Square
    val loadingShapes = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
    var fromShape by remember { mutableStateOf(restingShape) }
    var toShape by remember { mutableStateOf(restingShape) }
    var showIcon by remember { mutableStateOf(true) }
    val morphProgress = remember { Animatable(0f) }
    val morph = remember(fromShape, toShape) { Morph(fromShape, toShape) }

    LaunchedEffect(isConnecting) {
        suspend fun morphTo(next: RoundedPolygon) {
            if (fromShape == next) return
            toShape = next
            morphProgress.snapTo(0f)
            morphProgress.animateTo(1f, ConnectionMorphSpec)
            fromShape = next
            toShape = next
            morphProgress.snapTo(0f)
        }
        if (isConnecting) {
            showIcon = false
            while (true) {
                loadingShapes.forEach { morphTo(it) }
            }
        } else {
            showIcon = true
            if (fromShape != restingShape) {
                toShape = restingShape
                morphProgress.snapTo(0f)
                morphProgress.animateTo(1f, ConnectionMorphSpec)
            }
            fromShape = restingShape
            toShape = restingShape
            morphProgress.snapTo(0f)
        }
    }

    val containerColor by animateColorAsState(
        targetValue = when {
            isConnecting -> MaterialTheme.colorScheme.primary
            isConnected -> Color(0xFF00C853) // 成功绿（鲜亮）
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        // 主题切换窗口内直接跟随当前主题色，避免与全局色板渐变双重滤波
        animationSpec = if (snapColors) tween(0) else tween(350),
        label = "fabBg"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isConnecting || isConnected -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = if (snapColors) tween(0) else tween(350),
        label = "fabFg"
    )

    val morphClip = remember(morph) {
        GenericShape { size, _ ->
            val p = morph.toPath(progress = morphProgress.value)
            p.transform(Matrix().apply { scale(x = size.width, y = size.height) })
            p.translate(size.center - p.getBounds().center)
            addPath(p)
        }
    }
    Box(
        modifier = modifier
            .size(ConnectionFabSize)
            .shadow(ConnectionShadow, MaterialTheme.shapes.extraLarge, clip = false)
            .drawWithContent {
                val p = morph.toPath(progress = morphProgress.value)
                p.transform(Matrix().apply { scale(x = size.width, y = size.height) })
                p.translate(size.center - p.getBounds().center)
                drawPath(p, color = containerColor)
                drawContent()
            }
            .clip(morphClip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = showIcon,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (!isConnecting) {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.CheckCircle else Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = contentColor
                    )
                }
            }
            if (isConnecting) {
                // 连接中：无图标，只保留文字（形变本身即加载动画）
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = when {
                    isConnecting -> "连接中"
                    isConnected -> "连接成功"
                    else -> "点击连接"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1
            )
            // 连接成功后按钮内显示已连接时长（小字，随图标变色）
            if (isConnected && durationSeconds > 0) {
                Text(
                    text = MainViewModel.formatDuration(durationSeconds),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = contentColor.copy(alpha = 0.9f),
                    maxLines = 1
                )
            }
        }
    }
}

private val ConnectionMorphSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.1f
)
private val ConnectionFabSize = 92.dp
private val ConnectionShadow = 6.dp

// ============ 网速卡：FlClash 风格（标题+SVG | 右上实时速度 | 动态曲线） ============
@Composable
private fun SpeedCard(stats: VpnStats, history: List<SpeedSample>) {
    val sessionTotal = stats.downBytes + stats.upBytes
    val upColor = MaterialTheme.colorScheme.tertiary
    val downColor = MaterialTheme.colorScheme.primary
    // 数值丝滑过渡：0.3s 采样 + 150ms tween 补间，避免跳变
    val animatedUp by animateFloatAsState(
        targetValue = stats.upSpeed,
        animationSpec = tween(durationMillis = 150),
        label = "upSpeed"
    )
    val animatedDown by animateFloatAsState(
        targetValue = stats.downSpeed,
        animationSpec = tween(durationMillis = 150),
        label = "downSpeed"
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // 标题行：左侧小 SVG + “网络速度”；右侧上传/下载实时速度
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(downColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Speed, null,
                        modifier = Modifier.size(15.dp),
                        tint = downColor
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "网络速度",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                HeaderSpeed(
                    arrow = "↑",
                    color = upColor,
                    text = MainViewModel.formatSpeed(animatedUp)
                )
                Spacer(Modifier.width(14.dp))
                HeaderSpeed(
                    arrow = "↓",
                    color = downColor,
                    text = MainViewModel.formatSpeed(animatedDown)
                )
            }
            Spacer(Modifier.height(12.dp))
            // 动态速率曲线（0.3s 采样，平滑绘制）
            TrafficChart(history = history, upColor = upColor, downColor = downColor)
            Spacer(Modifier.height(10.dp))
            // 本次连接累计（名左量右）
            TrafficRow(
                label = "本次连接",
                value = MainViewModel.formatBytes(sessionTotal),
                highlight = false
            )
        }
    }
}

/** 标题行右侧的紧凑速度：↑ / ↓ + 数值，颜色与曲线一致 */
@Composable
private fun HeaderSpeed(arrow: String, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            arrow,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1
        )
    }
}

/**
 * 动态速率曲线：底部基线 + 上行/下行双曲线（tertiary / primary 分明）。
 * 平滑滚动：每点按「采样时刻」映射 x 坐标（时间轴），配合每帧重绘，
 * 整条曲线持续向左流动、新点从右缘滑入。
 * 流畅性设计：
 *  - 帧循环常驻且永不因采样重启（避免协程取消导致掉帧）；
 *  - 绘制路径直接构建、零中间对象分配；
 *  - 尾部追加"当前时刻"实时点贴住右缘，滚动无缺口；
 *  - 峰值缓慢衰减（不随新采样突跳），y 轴比例稳定。
 */
@Composable
private fun TrafficChart(
    history: List<SpeedSample>,
    upColor: Color,
    downColor: Color,
    modifier: Modifier = Modifier
) {
    val baselineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    // 常驻帧时钟：最新历史经 rememberUpdatedState 读取（不随数据变化重启协程）
    val latestHistory = rememberUpdatedState(history)
    var nowMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val lastT = latestHistory.value.lastOrNull()?.timeMs ?: 0L
            if (lastT == 0L || System.currentTimeMillis() - lastT > CHART_WINDOW_MS) {
                // 无新数据（未连接/空闲）：不空转帧循环，低频轮询等待数据
                nowMs = 0L
                delay(200)
            } else {
                withFrameNanos { nowMs = System.currentTimeMillis() }
            }
        }
    }
    // y 轴峰值：只增不突跳，无新点期间按帧缓慢回落（存于普通数组，避免 draw 阶段写 State）
    val peakHolder = remember { floatArrayOf(1f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
    ) {
        if (history.size < 2 || nowMs <= 0L) return@Canvas
        val w = size.width
        val h = size.height
        val pad = 2.dp.toPx()
        val pxPerMs = (w - pad * 2) / CHART_WINDOW_MS // 每毫秒水平位移（30s 窗口铺满全宽）
        // 峰值平滑：新峰值立即采用，无数据时每帧衰减 0.2%，曲线不突然被压缩/放大
        val curMax = history.maxOf { maxOf(it.upSpeed, it.downSpeed) }.coerceAtLeast(1f)
        peakHolder[0] = maxOf(curMax, peakHolder[0] * 0.998f)
        val maxV = peakHolder[0]
        fun yOf(v: Float): Float = h - pad - (v / maxV) * (h - pad * 2)

        /**
         * 平滑曲线 path（直接构建，无中间分配）。
         * 终点追加"当前时刻"实时点（最新值水平延伸至右缘），滚动无缺口。
         */
        fun curvePath(pick: (SpeedSample) -> Float): Path {
            val path = Path()
            var first = true
            var prevX = 0f
            var prevY = 0f
            var lastY = 0f
            for (s in history) {
                val x = (w - pad) - (nowMs - s.timeMs) * pxPerMs
                val y = yOf(pick(s))
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.quadraticBezierTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
                }
                prevX = x
                prevY = y
                lastY = y
            }
            // 最新点 → 当前时刻（右缘）：同值水平延伸，曲线尾部始终贴住右端
            path.lineTo(w - pad, lastY)
            return path
        }

        /** 平滑面积：曲线 + 底边闭合 */
        fun areaPath(curve: Path): Path {
            val p = Path()
            p.addPath(curve)
            p.lineTo(w - pad, h - pad)
            p.lineTo(pad, h - pad)
            p.close()
            return p
        }

        // 上行面积 + 曲线（tertiary，柔和的浅填充）
        if (history.any { it.upSpeed > 0f }) {
            val upCurve = curvePath { it.upSpeed }
            drawPath(areaPath(upCurve), upColor.copy(alpha = 0.10f))
            drawPath(
                upCurve,
                upColor,
                style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        // 下行面积 + 曲线（primary，主题色主视觉）
        val downCurve = curvePath { it.downSpeed }
        drawPath(areaPath(downCurve), downColor.copy(alpha = 0.14f))
        drawPath(
            downCurve,
            downColor,
            style = Stroke(width = 1.32.dp.toPx(), cap = StrokeCap.Round)
        )

        // 底部基线
        drawLine(
            color = baselineColor,
            start = Offset(pad, h - pad),
            end = Offset(w - pad, h - pad),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/** 曲线可视窗口：与 MainViewModel 采样节奏匹配（100ms × 300 点 = 30s 全宽） */
private const val CHART_WINDOW_MS = 30_000L

/** 累计流量行：名字在左、累计量在右 */
@Composable
private fun TrafficRow(label: String, value: String, highlight: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ---------------------------------------------------------------------------
// 配置卡片：名称 + 延迟 + 测延迟按钮 + 更多按钮（独立小卡，嵌在外框内）
// 点击卡片：未选中=切换连接，已选中=编辑
// 更多弹窗（AlertDialog）：对 xx 配置操作 → 复制链接 / 删除
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileRow(
    node: NodeConfig,
    isSelected: Boolean,
    snapColors: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPing: (done: () -> Unit) -> Unit,
    onCopyLink: () -> Unit
) {
    val context = LocalContext.current
    var pinging by remember { mutableStateOf(false) }
    var actionOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 选中/未选中底色丝滑过渡（未选中=独立浅卡，选中=主题色点缀）
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        // 主题切换窗口内直接跟随当前主题色，避免与全局色板渐变双重滤波
        animationSpec = if (snapColors) tween(0) else tween(220),
        label = "rowBg"
    )
    val nameColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = if (snapColors) tween(0) else tween(220),
        label = "rowName"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                node.displayName(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.ExtraBold,
                color = nameColor,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            // 放大间距：延迟数字
            Spacer(Modifier.width(10.dp))
            PingChip(node.pingMs, pinging)
            // 放大间距：测延迟按钮（闪电 SVG）
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = {
                pinging = true
                onPing { pinging = false }
            }, modifier = Modifier.size(38.dp)) {
                if (pinging) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.Bolt, "测延迟",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // 更多（三条杆）
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = { actionOpen = true }, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Filled.Menu, "更多",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // 更多操作弹窗：对 xx 配置操作（复制链接 / 删除 / 取消 横排一行）
    if (actionOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { actionOpen = false },
            icon = {
                Icon(
                    Icons.Filled.Menu, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            },
            title = { Text("对「${node.displayName()}」配置操作") },
            text = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 复制链接：主题色 + 淡主题色底
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            actionOpen = false
                            onCopyLink()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("复制链接", fontSize = 13.sp, maxLines = 1)
                    }
                    // 删除：红色 + 淡红色底
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            actionOpen = false
                            confirmDelete = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("删除", fontSize = 13.sp)
                    }
                    // 取消：中性灰 + 淡灰底
                    androidx.compose.material3.OutlinedButton(
                        onClick = { actionOpen = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            containerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {},
        )
    }

    // 删除确认弹窗
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = {
                Icon(
                    Icons.Filled.Delete, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(26.dp)
                )
            },
            title = { Text("删除配置？") },
            text = {
                Text(
                    "确定删除「${node.displayName()}」配置？删除后不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PingChip(ping: Int, pinging: Boolean) {
    // 状态码：-1 未测 / -2 超时(黄) / -3 不可达(红)；正常值按延迟分档
    val (text, color) = when {
        pinging -> "…" to MaterialTheme.colorScheme.outline
        ping == NodeConfig.PING_TIMEOUT ->
            "超时" to com.xfgken.Lumi.ui.theme.WarnYellow
        ping == NodeConfig.PING_UNREACHABLE ->
            "不可达" to MaterialTheme.colorScheme.error
        ping < 0 -> "--" to MaterialTheme.colorScheme.outline
        ping < 120 -> "${ping}ms" to MaterialTheme.colorScheme.primary
        ping < 300 -> "${ping}ms" to MaterialTheme.colorScheme.tertiary
        else -> "${ping}ms" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = Modifier.width(64.dp),
        textAlign = TextAlign.End
    )
}

@Composable
private fun EmptyCard(onAdd: () -> Unit) {
    Card(
        onClick = onAdd,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "还没有配置 · 点击添加",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 顶部小图标按钮：无底色无边框（仅图标 + MD3 涟漪），与标题同水平
// ---------------------------------------------------------------------------
@Composable
private fun TopIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// 关于弹窗：软件名 / 项目详细信息 / GitHub 项目跳转
// ---------------------------------------------------------------------------
@Composable
private fun AboutDialog(coreLoaded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "Lumi",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "轻量 Hysteria2 Android VPN 客户端",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                InfoRow("版本", "v1.4.0")
                InfoRow("作者", "xfgken")
                InfoRow("项目", "xfgken/Lumi")
                InfoRow("协议核心", "Hysteria2")
                InfoRow("转发栈", "gVisor + TUN")
                InfoRow(
                    "核心状态",
                    if (coreLoaded) "运行中" else "未就绪",
                    valueColor = if (coreLoaded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                // GitHub 项目跳转
                androidx.compose.material3.Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xfgken/Lumi"))
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(0.dp)
                ) {
                    GithubIcon(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "GitHub 项目 · xfgken/Lumi",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

/** 关于弹窗内信息行 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

/** GitHub Octocat 小猫图标（单色线性，随 tint 变色） */
@Composable
private fun GithubIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = GithubMark,
        contentDescription = "GitHub",
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

/** GitHub 官方 Mark（Octocat）矢量路径 */
private val GithubMark: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "GithubMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M12,0.3C5.4,0.3,0,5.7,0,12.3c0,5.3,3.4,9.8,8.2,11.4c0.6,0.1,0.8-0.3,0.8-0.6c0-0.3,0-1.1,0-2.1c-3.3,0.7-4-1.6-4-1.6c-0.5-1.4-1.3-1.8-1.3-1.8c-1.1-0.7,0.1-0.7,0.1-0.7c1.2,0.1,1.8,1.2,1.8,1.2c1,1.8,2.7,1.3,3.4,1c0.1-0.8,0.4-1.3,0.7-1.6c-2.6-0.3-5.3-1.3-5.3-5.7c0-1.3,0.5-2.3,1.2-3.1c-0.1-0.3-0.5-1.5,0.1-3.1c0,0,1-0.3,3.3,1.2c1-0.3,2-0.4,3-0.4c1,0,2,0.1,3,0.4c2.3-1.5,3.3-1.2,3.3-1.2c0.6,1.6,0.2,2.8,0.1,3.1c0.7,0.8,1.2,1.8,1.2,3.1c0,4.4-2.7,5.4-5.3,5.7c0.4,0.4,0.8,1.1,0.8,2.2c0,1.6,0,2.9,0,3.3c0,0.3,0.2,0.7,0.8,0.6c4.8-1.6,8.2-6.1,8.2-11.4C24,5.7,18.6,0.3,12,0.3z"
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black)
    ).build()
}