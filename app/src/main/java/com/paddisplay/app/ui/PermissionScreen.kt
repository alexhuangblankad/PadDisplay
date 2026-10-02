package com.paddisplay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paddisplay.app.shizuku.ShizukuManager

/**
 * Shizuku 权限引导卡片（任务书第 10 节的中文提示）。
 */
@Composable
fun PermissionCard(vm: MainViewModel, ui: MainViewModel.UiState) {
    val stage = ui.shizuku.stage
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "系统权限",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (stage) {
                    ShizukuManager.Stage.NOT_INSTALLED ->
                        "未检测到 Shizuku。\n\nPadDisplay 需要 Shizuku 权限来控制外接显示器。\n这不需要 Root。\n\n请先安装 Shizuku（可在应用商店或 GitHub 获取）。"
                    ShizukuManager.Stage.NOT_RUNNING ->
                        "Shizuku 未运行。\n\n请先打开 Shizuku 并启动服务。\n启动方式：无线调试（Android 11+，无需电脑）或 adb。\n全程不需要 Root。"
                    ShizukuManager.Stage.NOT_AUTHORIZED ->
                        "Shizuku 正在运行，但本应用尚未获得授权。\n\nPadDisplay 需要 Shizuku 权限来控制外接显示器。\n这不需要 Root。"
                    ShizukuManager.Stage.BINDING ->
                        "已授权，正在启动特权服务（UserService）…"
                    ShizukuManager.Stage.ERROR ->
                        "服务异常：\n${ui.shizuku.message}"
                    ShizukuManager.Stage.CONNECTED ->
                        "已就绪。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (stage) {
                    ShizukuManager.Stage.NOT_AUTHORIZED -> {
                        Button(onClick = { vm.requestShizuku() }) { Text("授权 Shizuku") }
                    }
                    ShizukuManager.Stage.NOT_RUNNING, ShizukuManager.Stage.NOT_INSTALLED -> {
                        Button(onClick = { vm.openShizukuApp() }) { Text("打开 Shizuku") }
                    }
                    else -> Unit
                }
                OutlinedButton(onClick = { vm.retryShizuku() }) { Text("重新检测") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "提示：Shizuku 授权后，PadDisplay 会以 shell(uid=2000) 身份调用系统服务，" +
                    "从而做到「只改外接屏、只关内屏」。" +
                    "即使不授权，第一页的显示器枚举与 supportedModes 读取依然可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 诊断信息页（任务书第 15 节）。
 */
@Composable
fun DiagnosticsScreen(
    text: String,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "诊断信息",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCopy) { Text("复制诊断信息") }
                    OutlinedButton(onClick = onClose) { Text("关闭") }
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    Text(
                        text = text.ifEmpty { "正在收集…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
