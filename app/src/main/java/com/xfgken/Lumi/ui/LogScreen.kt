package com.xfgken.Lumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfgken.Lumi.data.LogEntity
import com.xfgken.Lumi.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志窗口（居中 Dialog 样式，近全屏）：标题 + 复制/清空/关闭 + 小字列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    vm: MainViewModel,
    onClose: (() -> Unit)? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        // 顶部标题行（与首页同字号同色，紧凑贴边让内容占满；上/右边距一致）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "运行日志",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            // 复制全部
            Surface(
                onClick = {
                    if (logs.isEmpty()) return@Surface
                    val sb = StringBuilder()
                    logs.forEach { log ->
                        val tag = when (log.level) {
                            "E" -> "E"
                            "W" -> "W"
                            else -> "I"
                        }
                        sb.append('[').append(timeFmt.format(Date(log.timeMs)))
                            .append("] ").append(tag).append(": ").append(log.message).append('\n')
                    }
                    val clip = android.content.ClipData.newPlainText(
                        "lumi-log-all",
                        sb.toString().trimEnd('\n')
                    )
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager).setPrimaryClip(clip)
                    Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.ContentCopy, "复制全部",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 清空日志（浅红底）
            Surface(
                onClick = { vm.clearLogs() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.DeleteSweep, "清空日志",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // 关闭（窗口右上角 ✕，灰色实底圆钮，与复制/清空同尺寸）
            if (onClose != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { onClose() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close, "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogRow(log, timeFmt)
                }
            }
        }
    }
}

/** 原生日志行：第一行=时间+类型标签（日志开头感），第二行=正文占满整行；整行颜色对应类型 */
@Composable
private fun LogRow(log: LogEntity, timeFmt: SimpleDateFormat) {
    val isError = log.level == "E"
    val isWarn = log.level == "W"
    // 类型主色：错误=红(error)、警告=黄(WarnYellow)、信息=青绿(tertiary)
    val tagColor = when {
        isError -> MaterialTheme.colorScheme.error
        isWarn -> com.xfgken.Lumi.ui.theme.WarnYellow
        else -> MaterialTheme.colorScheme.tertiary
    }
    val tagText = when {
        isError -> "错误"
        isWarn -> "警告"
        else -> "信息"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        // 第一行：时间 + 类型标签，同一个淡色实底块（该条日志的开头标识，无外描边，文字四周留少量边距）
        Surface(
            shape = RoundedCornerShape(2.dp),
            color = tagColor.copy(alpha = 0.13f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFmt.format(Date(log.timeMs)),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = tagColor
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = tagText,
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = tagColor
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        // 第二行：正文，等宽小号，占满整行宽度
        Text(
            text = log.message,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = tagColor,
            modifier = Modifier.fillMaxWidth()
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}