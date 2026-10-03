package com.paddisplay.app.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paddisplay.app.ui.MainViewModel
import com.paddisplay.app.ui.ConfirmCountdownDialog
import kotlinx.coroutines.flow.update

@Composable
fun DesktopHub(vm: MainViewModel, ui: MainViewModel.UiState, onControls: () -> Unit) {
    val context = LocalContext.current
    val state by DesktopState.state.collectAsState()
    val store = remember { DesktopStore(context) }
    LaunchedEffect(ui.shizuku.canControl) { vm.refreshLaunchableApps() }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PadDisplay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (state.running) "主机已开启 · 外屏工作空间" else "平板与外屏，随时切换",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onControls) { Text("高级设置") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button({ vm.startHostMode() }, enabled = ui.shizuku.canControl && ui.externalConnected && !ui.busy && !state.running) { Text("开启主机") }
                OutlinedButton({ DesktopService.send(context, "stop") }, enabled = state.running && !ui.busy) { Text("退出并恢复") }
                OutlinedButton({ if (ui.internalTurnedOff) vm.turnOnInternal() else vm.turnOffInternal() },
                    enabled = ui.shizuku.canControl && ui.externalConnected && !ui.busy) {
                    Text(if (ui.internalTurnedOff) "点亮平板屏幕" else "关闭平板屏幕")
                }
                Spacer(Modifier.weight(1f))
                Text(if (!ui.shizuku.canControl) "请在高级设置连接 Shizuku" else if (!ui.externalConnected) "等待外屏" else "外屏已连接",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        AppGrid(ui.launchableApps.ifEmpty { state.apps }, store.favorites(),
            ui.shizuku.canControl && ui.externalConnected && !ui.busy,
            { if (state.running) DesktopService.send(context, "launch", it.component) else vm.launchAppOnExternal(it) },
            { store.toggleFavorite(it); DesktopState.state.update { snapshot -> snapshot.copy(favorites = store.favorites()) } },
            Modifier.weight(1f))
        val detail = if (state.running || state.message != "主机模式未启动") state.message else ui.lastResult
        val summary = when {
            detail == null -> "选择应用，在外屏开始工作。"
            detail.contains("RESULT_OK=false") -> "操作未完成，请在高级设置查看详情。"
            detail.contains("RESULT_OK=true") -> if (state.running) "外屏会话已更新。" else "恢复已执行，详情保存在高级设置。"
            else -> detail
        }
        Text(summary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
    ui.pendingConfirm?.let { ConfirmCountdownDialog(it, vm::confirmPending, vm::cancelPending) }
}
