package com.xfgken.Lumi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.xfgken.Lumi.core.Hysteria2UriParser
import com.xfgken.Lumi.model.NodeConfig

/**
 * 添加 / 编辑节点 BottomSheet。
 * 标题行右侧 = 操作按钮：链接导入(粘贴/解析) · 手动填写(取消/添加)
 * Tab1：粘贴 hysteria2:// 链接导入（自动解析预览）
 * Tab2：手动填写完整字段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeEditorSheet(
    initial: NodeConfig?,
    onSave: (NodeConfig) -> Unit,
    onCancel: () -> Unit = {}
) {
    val isEdit = initial != null
    val context = LocalContext.current
    val fieldShape = RoundedCornerShape(16.dp)

    // 表单状态
    var uriText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var server by remember { mutableStateOf(initial?.server ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var sni by remember { mutableStateOf(initial?.sni ?: "") }
    var insecure by remember { mutableStateOf(initial?.insecure ?: false) }
    var obfsEnabled by remember { mutableStateOf(initial?.obfsType == "salamander") }
    var obfsPassword by remember { mutableStateOf(initial?.obfsPassword ?: "") }

    var tab by remember { mutableStateOf(if (isEdit) 1 else 0) }
    var showParseDialog by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<NodeConfig?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    // ---- 标题行右侧按钮组 ----
    val linkTab = tab == 0 && !isEdit

    // 校验并保存（手动表单）
    val handleSave: () -> Unit = {
        val p = port.toIntOrNull()
        when {
            server.isBlank() -> Toast.makeText(context, "服务器地址不能为空", Toast.LENGTH_SHORT).show()
            p == null || p !in 1..65535 -> Toast.makeText(context, "端口无效", Toast.LENGTH_SHORT).show()
            else -> onSave(
                NodeConfig(
                    id = initial?.id ?: 0L,
                    name = name.ifBlank { server },
                    server = server.trim(),
                    port = p,
                    password = password,
                    sni = sni.trim(),
                    insecure = insecure,
                    obfsType = if (obfsEnabled) "salamander" else "",
                    obfsPassword = if (obfsEnabled) obfsPassword else "",
                    upMbps = 0,
                    downMbps = 0,
                    pingMs = initial?.pingMs ?: -1,
                    isSelected = initial?.isSelected ?: false
                )
            )
        }
    }

    // 解析链接并回填到手动表单
    val handleParse: () -> Unit = {
        parseError = null
        preview = null
        try {
            val parsed = Hysteria2UriParser.parse(uriText)
            name = parsed.name
            server = parsed.server
            port = parsed.port.toString()
            password = parsed.password
            sni = parsed.sni
            insecure = parsed.insecure
            obfsEnabled = parsed.obfsType == "salamander"
            obfsPassword = parsed.obfsPassword
            tab = 1
            Toast.makeText(context, "解析成功，请确认信息", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            parseError = e.message ?: "解析失败"
        }
    }

    // 粘贴剪贴板
    val handlePaste: () -> Unit = {
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString()?.let { uriText = it }
        }
    }

    // 高度策略：固定约 70%，打开即丝滑展开
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.72f)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ============ 标题行：文字 + 最右按钮组 ============
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEdit) "编辑节点" else "添加配置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (linkTab) {
                // 链接导入：粘贴 / 解析
                TextButton(onClick = handlePaste) {
                    Icon(Icons.Filled.ContentPaste, null, Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("粘贴")
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = handleParse) { Text("解析") }
            } else {
                // 手动填写 / 编辑：取消 / 添加(保存)
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = handleSave) {
                    Text(if (isEdit) "保存" else "添加")
                }
            }
        }

        // 仅新增时提供 Tab 切换（MD3 胶囊滑块样式，加高胶囊）
        if (!isEdit) {
            PillSelector(
                options = listOf("链接导入", "手动填写"),
                selectedIndex = tab,
                onSelect = { tab = it },
                height = 52.dp,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        AnimatedVisibility(
            visible = linkTab,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
        ) {
            // ---- Tab1：URI 导入 ----
            Column {
            OutlinedTextField(
                                shape = fieldShape,
                value = uriText,
                onValueChange = { uriText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("请输入 hysteria2:// 链接") },
                minLines = 2,
                maxLines = 4
            )
            parseError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showParseDialog = true }) {
                    Icon(Icons.Filled.Info, null, Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("支持的参数")
                }
            }
            }
        }
        AnimatedVisibility(
            visible = !linkTab,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
        ) {
            // ---- Tab2：手动表单 ----
            Column {
            OutlinedTextField(
                                shape = fieldShape,
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称") },
                placeholder = { Text("请输入配置名称") },
                singleLine = true
            )
            OutlinedTextField(
                                shape = fieldShape,
                value = server,
                onValueChange = { server = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器地址") },
                placeholder = { Text("example.com 或 1.2.3.4") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                                        shape = fieldShape,
                    value = port,
                    onValueChange = { port = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                                        shape = fieldShape,
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.weight(2f),
                    label = { Text("密码 (auth)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
            OutlinedTextField(
                                shape = fieldShape,
                value = sni,
                onValueChange = { sni = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("SNI (可选)") },
                placeholder = { Text("留空默认使用服务器地址") },
                singleLine = true
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("允许不安全 (跳过证书校验)", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = insecure, onCheckedChange = { insecure = it })
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Salamander 混淆", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = obfsEnabled, onCheckedChange = { obfsEnabled = it })
            }
            // 混淆密码输入框：开/关均有渐入渐出动画
            AnimatedVisibility(
                visible = obfsEnabled,
                enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(200))
            ) {
                OutlinedTextField(
                                        shape = fieldShape,
                    value = obfsPassword,
                    onValueChange = { obfsPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("混淆密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
            }
        }
    }

    // 参数说明对话框
    if (showParseDialog) {
        AlertDialog(
            onDismissRequest = { showParseDialog = false },
            title = { Text("URI 支持的参数") },
            text = {
                Text(
                    """hysteria2://密码@服务器:端口/?参数#名称

• sni=域名          TLS SNI
• insecure=1        跳过证书校验
• obfs=salamander&obfs-password=xxx   混淆
• up=100&down=200   上下行带宽 Mbps""",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showParseDialog = false }) { Text("知道了") }
            }
        )
    }
}