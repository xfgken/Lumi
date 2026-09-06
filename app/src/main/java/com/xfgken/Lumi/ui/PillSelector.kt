package com.xfgken.Lumi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MD3 风格胶囊单选器：点击选项时，主题色滑块平滑滑动到该项（非真正的拖动）。
 * 无勾选标记，选中项文字为 onPrimary。
 */
@Composable
internal fun PillSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    snapColors: Boolean = false,
    height: androidx.compose.ui.unit.Dp = 44.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        val itemWidth = maxWidth / options.size
        // 滑块位置：点击切换时平滑滑动
        val sliderOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = tween(280),
            label = "pillSlider"
        )
        // 滑块
        Box(
            Modifier
                .offset(x = sliderOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
        )
        // 选项（文字层在滑块之上，可点击）
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    // 主题切换窗口内直接跟随当前主题色（snap），避免与全局色板渐变双重滤波
                    animationSpec = if (snapColors) tween(0) else tween(220),
                    label = "pillText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = fontSize,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        ),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}