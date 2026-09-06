package com.xfgken.Lumi.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 警告/超时专用黄（Material 规范外的语义色：信息青绿 / 警告黄 / 错误红） */
val WarnYellow: Color = Color(0xFFF9AB00)

/**
 * Lumi 唯一主题色板：MD3 蓝主色 + 纯净中性表面。
 * 浅色：微冷白底 + 蓝灰卡片；深色：安卓系统设置同款正灰黑（无紫调），文字高对比清晰。
 */
private fun blueScheme(dark: Boolean): androidx.compose.material3.ColorScheme =
    if (dark) darkColorScheme(
        // —— 主色系（暗色提亮）——
        primary = Color(0xFFA8C7FA),         // tone80 亮蓝
        onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF0842A0),// tone30
        onPrimaryContainer = Color(0xFFD3E3FD),
        inversePrimary = Color(0xFF0B57D0),
        // —— 辅助（冷灰蓝）——
        secondary = Color(0xFFBEC6DC),
        onSecondary = Color(0xFF262F3F),
        secondaryContainer = Color(0xFF3B4757),
        onSecondaryContainer = Color(0xFFD7E3F8),
        // —— 强调（柔和青）——
        tertiary = Color(0xFF4DD0E1),
        onTertiary = Color(0xFF00363D),
        tertiaryContainer = Color(0xFF004F58),
        onTertiaryContainer = Color(0xFFB2EBF2),
        // —— 背景/表面（正灰黑，无紫调；安卓系统设置同款观感）——
        background = Color(0xFF121212),
        onBackground = Color(0xFFE8E8E8),
        surface = Color(0xFF121212),
        onSurface = Color(0xFFE8E8E8),
        surfaceVariant = Color(0xFF2E2E2E),
        onSurfaceVariant = Color(0xFFC6C6C6),
        outline = Color(0xFF8F8F8F),
        outlineVariant = Color(0xFF3A3A3A),
        // —— MD3 表面层级（灰黑逐级略亮，卡片清晰浮起）——
        surfaceDim = Color(0xFF121212),
        surfaceBright = Color(0xFF363636),
        surfaceContainerLowest = Color(0xFF0C0C0C),
        surfaceContainerLow = Color(0xFF1C1C1C),   // 卡片底
        surfaceContainer = Color(0xFF222222),      // 导航栏/中层
        surfaceContainerHigh = Color(0xFF2A2A2A),
        surfaceContainerHighest = Color(0xFF333333)
    ) else lightColorScheme(
        // —— 主色系 ——
        primary = Color(0xFF0B57D0),         // tone40 Google 蓝
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E3FD),// tone90 浅蓝容器
        onPrimaryContainer = Color(0xFF041E49),
        inversePrimary = Color(0xFFA8C7FA),
        // —— 辅助（冷灰蓝，系统感）——
        secondary = Color(0xFF535F70),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD7E3F8),
        onSecondaryContainer = Color(0xFF111C2B),
        // —— 强调（柔和青）——
        tertiary = Color(0xFF00838F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB2EBF2),
        onTertiaryContainer = Color(0xFF002022),
        // —— 背景/表面（近白微冷，MD3 软件常见底色）——
        background = Color(0xFFF7F9FC),
        onBackground = Color(0xFF141414),
        surface = Color(0xFFF7F9FC),
        onSurface = Color(0xFF141414),
        surfaceVariant = Color(0xFFE1E6EE),
        onSurfaceVariant = Color(0xFF41464F),
        outline = Color(0xFF73777F),
        outlineVariant = Color(0xFFC3C7CF),
        // —— MD3 表面层级（蓝灰浅色系，卡片从背景浮起）——
        surfaceDim = Color(0xFFD9DEE8),
        surfaceBright = Color(0xFFF7F9FC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEEF1F7),   // 卡片底
        surfaceContainer = Color(0xFFE8EBF2),      // 导航栏/中层
        surfaceContainerHigh = Color(0xFFE1E5ED),
        surfaceContainerHighest = Color(0xFFDBDFE8)
    )

/**
 * Lumi Material3 主题。
 *
 * @param mode 0=跟随系统 1=浅色 2=深色
 */
@Composable
fun LumiTheme(
    mode: Int = 0,
    content: @Composable () -> Unit
) {
    val darkTheme = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    // 全字段颜色过渡：深/浅切换时所有角色色平滑渐变（无 Crossfade 双树、不闪）
    val scheme = animatedColorScheme(blueScheme(darkTheme))

    // 深/浅切换时同步系统栏图标明暗 + 窗口底色，避免状态栏区域视觉割裂（无闪烁）
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        // 窗口底色跟随主题：杜绝过渡瞬间露出系统默认浅色底（白闪）
        window.decorView.setBackgroundColor(scheme.background.toArgb())
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        content = content
    )
}

/** 将目标 ColorScheme 的所有角色色做 400ms 平滑过渡（主题/配色切换动画） */
@Composable
private fun animatedColorScheme(target: androidx.compose.material3.ColorScheme): androidx.compose.material3.ColorScheme {
    val spec = tween<Color>(durationMillis = 420)
    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val inversePrimary by animateColorAsState(target.inversePrimary, spec, label = "inversePrimary")
    val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
    val onSecondary by animateColorAsState(target.onSecondary, spec, label = "onSecondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "tertiary")
    val onTertiary by animateColorAsState(target.onTertiary, spec, label = "onTertiary")
    val tertiaryContainer by animateColorAsState(target.tertiaryContainer, spec, label = "tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(target.onTertiaryContainer, spec, label = "onTertiaryContainer")
    val background by animateColorAsState(target.background, spec, label = "background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant")
    val surfaceTint by animateColorAsState(target.surfaceTint, spec, label = "surfaceTint")
    val inverseSurface by animateColorAsState(target.inverseSurface, spec, label = "inverseSurface")
    val inverseOnSurface by animateColorAsState(target.inverseOnSurface, spec, label = "inverseOnSurface")
    val error by animateColorAsState(target.error, spec, label = "error")
    val onError by animateColorAsState(target.onError, spec, label = "onError")
    val errorContainer by animateColorAsState(target.errorContainer, spec, label = "errorContainer")
    val onErrorContainer by animateColorAsState(target.onErrorContainer, spec, label = "onErrorContainer")
    val outline by animateColorAsState(target.outline, spec, label = "outline")
    val outlineVariant by animateColorAsState(target.outlineVariant, spec, label = "outlineVariant")
    val scrim by animateColorAsState(target.scrim, spec, label = "scrim")
    val surfaceBright by animateColorAsState(target.surfaceBright, spec, label = "surfaceBright")
    val surfaceDim by animateColorAsState(target.surfaceDim, spec, label = "surfaceDim")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow")
    val surfaceContainerLowest by animateColorAsState(target.surfaceContainerLowest, spec, label = "surfaceContainerLowest")
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}