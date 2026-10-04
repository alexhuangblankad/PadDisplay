package com.paddisplay.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paddisplay.app.desktop.*

@Composable
fun MainScreen(vm: MainViewModel, ui: MainViewModel.UiState) {
    val context = LocalContext.current
    val state by DesktopState.state.collectAsState()
    val store = remember { DesktopStore(context) }
    val appearance = rememberAppearance(context)
    var tab by remember { mutableIntStateOf(0) }
    var freeform by remember { mutableStateOf(store.freeform) }
    var reconnect by remember { mutableStateOf(store.autoReconnect) }
    var hideGames by remember { mutableStateOf(store.hideForGames) }
    val external = ui.displays.firstOrNull { it.isExternal }
    val enabled = ui.shizuku.canControl && !ui.busy && ui.pendingConfirm == null
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("高级设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("按需要调整，桌面保持简洁。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(vm::refreshDisplays) { Text("刷新") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("桌面与外观", "显示", "音频", "窗口", "诊断").forEachIndexed { index, title ->
                FilterChip(tab == index, { tab = index }, label = { Text(title) })
            }
        }
        if (tab == 3) TaskPanel(state, Modifier.weight(1f)) else LazyColumn(Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                0 -> {
                    item { SettingsGroup("外观") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Appearance.entries.forEach { mode -> FilterChip(mode == appearance, { ThemeSettings.write(context, mode) }, label = { Text(mode.label) }) }
                        }
                    } }
                    item { SettingsGroup("桌面行为") {
                        SettingsToggle("自由窗口启动", "系统支持时，应用以可调整窗口打开。", freeform) { freeform = it; store.freeform = it }
                        SettingsToggle("原外屏重连后恢复", "只匹配同一显示器。", reconnect) { reconnect = it; store.autoReconnect = it }
                        SettingsToggle("Moonlight 自动隐藏 Dock", "保留系统原生鼠标输入。", hideGames) { hideGames = it; store.hideForGames = it }
                        Text("全屏默认隐藏 Dock、三键和窗口控件。鼠标在底边停留两秒或点击底边唤出，15 秒后隐藏；Alt+Shift / F9 为快捷入口。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("开启全屏导航快捷键") }
                            TextButton({ DesktopService.send(context, "escape") }, enabled = state.running) { Text("唤出全屏导航") }
                        }
                        Text("快捷键需启用辅助服务；单独左 Alt 放行。串流捕获鼠标时可能无法底边悬停，可在平板主机模式通知中点“唤出导航”。Fn+F9 需键盘上报 F9。", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { SettingsGroup("权限与鼠标") {
                        Text(ui.shizuku.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(vm::requestShizuku) { Text("授权 Shizuku") }
                            TextButton(vm::openShizukuApp) { Text("打开 Shizuku") }
                            OutlinedButton({ context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }) { Text("任务栏悬浮权限") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(vm::returnMouseToTablet, enabled = enabled && !state.running) { Text("鼠标回平板") }
                            TextButton({ DesktopService.send(context, "retry") }, enabled = enabled && state.running) { Text("重试外屏鼠标绑定") }
                        }
                    } }
                    item { AutomationCard(vm) }
                    item { SettingsGroup("恢复") {
                        OutlinedButton(vm::restoreAll, enabled = enabled) { Text("一键还原所有设置") }
                    } }
                }
                1 -> {
                    if (external == null) item { SettingsGroup("外接显示器") { Text("连接物理外屏后可调整显示。") } }
                    else {
                        item { SettingsGroup(external.name) {
                            Text(external.currentMode?.label ?: "模式读取中", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MainViewModel.DisplayMode.entries.filter { it != MainViewModel.DisplayMode.UNKNOWN }.forEach { mode ->
                                    FilterChip(ui.displayMode == mode, { vm.setDisplayMode(mode) }, enabled = enabled, label = { Text(mode.label) })
                                }
                            }
                        } }
                        item { SettingsGroup("界面缩放") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(75,100,125,150,200).forEach { percent -> OutlinedButton({ vm.adjustExternalScale(percent) }, enabled = enabled) { Text("$percent%") } }
                            }
                            TextButton(vm::restoreExternalScale, enabled = enabled) { Text("恢复原缩放") }
                        } }
                        item { ResolutionPicker(vm, ui, external) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ vm.applyResolution() }, enabled = enabled) { Text("应用模式") }
                            OutlinedButton(vm::applyBestResolution, enabled = enabled) { Text("最佳分辨率") }
                            TextButton(vm::clearForcedSize, enabled = enabled) { Text("恢复尺寸") }
                        } }
                    }
                }
                2 -> item { AudioOutputCard(vm, ui) }
                4 -> {
                    item { SettingsGroup("诊断工具") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(vm::buildDiagnostics) { Text("完整诊断") }
                            TextButton(vm::probePointerDisplay, enabled = enabled) { Text("鼠标归属") }
                            TextButton(vm::runModeSelfCheck, enabled = enabled) { Text("显示模式") }
                        }
                        Text(listOfNotNull(ui.lastResult, state.message).distinct().joinToString("\n\n"), style = MaterialTheme.typography.bodySmall)
                    } }
                    if (ui.displays.size >= 2) item { RoleOverrideCard(vm, ui) }
                    item { EventLogCard(ui) }
                }
            }
        }
    }
    ui.pendingConfirm?.let { ConfirmCountdownDialog(it, vm::confirmPending, vm::cancelPending) }
    if (ui.showDiagnostics) DiagnosticsScreen(ui.diagnostics, { vm.copyDiagnostics(context) }, vm::closeDiagnostics)
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingsToggle(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, change)
    }
}
