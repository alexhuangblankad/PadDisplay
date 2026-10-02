package com.paddisplay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 防黑屏确认倒计时（任务书第 9 节）。
 *
 * 切换分辨率后弹出，15 秒内不点「保留」就自动回滚到切换前配置，
 * 避免「错误 Mode → 外屏黑屏 → 无法操作」。
 */
@Composable
fun ConfirmCountdownDialog(
    pending: MainViewModel.PendingConfirm,
    onKeep: () -> Unit,
    onCancel: () -> Unit,
) {
    val total = com.paddisplay.app.automation.DisplayHotplugManager.CONFIRM_TIMEOUT_SECONDS.toFloat()
    val progress = (pending.secondsLeft / total).coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = { /* 不允许点外部消失，必须明确选择 */ },
        title = {
            Text(pending.title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(pending.detail, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${pending.secondsLeft} 秒后自动恢复切换前配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "如果画面正常，请点「保留」；如果外接屏黑屏或花屏，请等它自动恢复，" +
                        "或点「立即恢复」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onKeep,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) { Text("保留") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("立即恢复") }
        },
    )
}
