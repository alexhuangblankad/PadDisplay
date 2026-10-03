package com.paddisplay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 主界面（任务书第 11 节：极简、单页、不要五六层设置页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, ui: MainViewModel.UiState) {
    val context = LocalContext.current
    val internal = ui.displays.firstOrNull { it.isInternal }
    val external = ui.displays.firstOrNull { it.isExternal }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PadDisplay", fontWeight = FontWeight.Bold)
                        Text(
                            "外接显示器控制面板 v" + com.paddisplay.app.BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { vm.buildDiagnostics() }) { Text("诊断信息") }
                    TextButton(onClick = { vm.refreshDisplays() }) { Text("刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SystemStatusCard(ui = ui) }

            if (ui.shizuku.stage != com.paddisplay.app.shizuku.ShizukuManager.Stage.CONNECTED) {
                item { PermissionCard(vm, ui) }
            }

            // ---------------- 内置显示器 ----------------
            item {
                if (internal != null) {
                    DisplayCard(display = internal, isInternal = true)
                } else {
                    NoDisplayCard("内置显示器", "未识别出内置屏（可在下方手动指定）")
                }
            }

            if (internal != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.turnOffInternal() },
                            enabled = ui.shizuku.canControl && !ui.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("关闭平板屏幕") }
                        OutlinedButton(
                            onClick = { vm.turnOnInternal() },
                            enabled = ui.shizuku.canControl && !ui.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("重新打开平板屏幕") }
                    }
                }
                if (ui.internalTurnedOff) {
                    item {
                        Text(
                            "注意：内屏已关闭。拔掉外接屏会自动恢复；也可以点上面的按钮手动恢复。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            // ---------------- 外接显示器 ----------------
            item {
                if (external != null) {
                    DisplayCard(display = external, isInternal = false)
                } else {
                    NoDisplayCard(
                        "外接显示器",
                        "未检测到外接屏\n请通过 USB-C / DisplayPort Alt Mode 连接显示器",
                    )
                }
            }

            if (external != null) {
                item { DisplayModeCard(vm = vm, ui = ui, external = external) }
                item { AudioOutputCard(vm = vm, ui = ui) }
                item { ResolutionPicker(vm = vm, ui = ui, external = external) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.applyResolution() },
                            enabled = ui.shizuku.canControl && !ui.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("应用") }
                        Button(
                            onClick = { vm.applyBestResolution() },
                            enabled = ui.shizuku.canControl && !ui.busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("使用最佳分辨率") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.clearForcedSize() },
                            enabled = ui.shizuku.canControl && !ui.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("清除尺寸覆盖") }
                        OutlinedButton(
                            onClick = { vm.runModeSelfCheck() },
                            enabled = ui.shizuku.canControl && !ui.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Mode 自检") }
                    }
                }
                item { SupportedModesList(external) }
            }

            item { AutomationCard(vm = vm) }

            if (ui.displays.size >= 2) {
                item { RoleOverrideCard(vm, ui) }
            }

            item { EventLogCard(ui = ui) }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    ui.pendingConfirm?.let { pending ->
        ConfirmCountdownDialog(
            pending = pending,
            onKeep = { vm.confirmPending() },
            onCancel = { vm.cancelPending() },
        )
    }

    if (ui.showDiagnostics) {
        DiagnosticsScreen(
            text = ui.diagnostics,
            onCopy = { vm.copyDiagnostics(context) },
            onClose = { vm.closeDiagnostics() },
        )
    }
}

@Composable
private fun SystemStatusCard(ui: MainViewModel.UiState) {
    val stage = ui.shizuku.stage
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("系统状态")
            Spacer(Modifier.height(8.dp))
            StatusRow(
                ok = stage == com.paddisplay.app.shizuku.ShizukuManager.Stage.CONNECTED,
                text = ui.shizuku.message,
            )
            Spacer(Modifier.height(4.dp))
            StatusRow(
                ok = ui.shizuku.serviceUid == 2000,
                warn = stage == com.paddisplay.app.shizuku.ShizukuManager.Stage.BINDING,
                text = if (ui.shizuku.serviceUid == 2000) {
                    "系统控制权限已获得（shell uid=2000）"
                } else {
                    "UserService uid=" + ui.shizuku.serviceUid + "（未就绪）"
                },
            )
            Spacer(Modifier.height(4.dp))
            StatusRow(
                ok = ui.externalConnected,
                warn = !ui.externalConnected,
                text = if (ui.externalConnected) {
                    "已检测到外接显示器"
                } else {
                    "未检测到外接显示器（请用 USB-C 连接）"
                },
            )
            if (ui.busy) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        Modifier
                            .width(16.dp)
                            .height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在执行…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NoDisplayCard(title: String, message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AutomationCard(vm: MainViewModel) {
    val autoNative by vm.autoNative.collectAsState(initial = false)
    val autoInternalOff by vm.autoInternalOff.collectAsState(initial = false)
    val autoRestore by vm.autoRestore.collectAsState(initial = true)
    val forcedFallback by vm.forcedSizeFallback.collectAsState(initial = false)
    val moonlight by vm.launchMoonlight.collectAsState(initial = false)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("自动操作")
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "接入外屏后使用最佳分辨率",
                subtitle = "最高分辨率 + 默认 60 Hz（USB-C 兼容性最好）",
                checked = autoNative,
                onChange = { vm.setAutoNative(it) },
            )
            ToggleRow(
                title = "外屏连接后关闭平板内屏",
                subtitle = "真正关闭 panel 电源，不是黑色遮罩",
                checked = autoInternalOff,
                onChange = { vm.setAutoInternalOff(it) },
            )
            ToggleRow(
                title = "外屏拔出后自动恢复内屏",
                subtitle = "failsafe，强烈建议保持开启",
                checked = autoRestore,
                onChange = { vm.setAutoRestore(it) },
            )
            ToggleRow(
                title = "真实 Mode 失败时回退到逻辑尺寸覆盖",
                subtitle = "IWindowManager.setForcedDisplaySize（per-display，不影响内屏）",
                checked = forcedFallback,
                onChange = { vm.setForcedSizeFallback(it) },
            )
            ToggleRow(
                title = "外屏连接成功后启动 Moonlight",
                subtitle = "未安装则忽略（不集成 Moonlight SDK）",
                checked = moonlight,
                onChange = { vm.setLaunchMoonlight(it) },
            )
        }
    }
}

/**
 * 外接屏显示模式（对标 Windows 的 Win+P）。
 *
 * 三个选项的选择依据：
 * - **扩展**：外接屏成为独立屏幕（走 IWindowManager.setWindowingMode 打破镜像）
 * - **复制**：系统行为，Android 14+ 已移除强制镜像 API，这里如实说明
 * - **仅外接屏**：关闭内屏 panel，外接屏继续工作
 */
@Composable
private fun DisplayModeCard(
    vm: MainViewModel,
    ui: MainViewModel.UiState,
    external: com.paddisplay.app.display.DisplaySnapshot,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("外接屏显示模式")
            Spacer(Modifier.height(4.dp))
            Text(
                "对标 Windows 的「复制 / 扩展 / 仅第二屏幕」。默认判定：${ui.displayMode.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            MainViewModel.DisplayMode.entries
                .filter { it != MainViewModel.DisplayMode.UNKNOWN }
                .forEach { mode ->
                    val selected = ui.displayMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                enabled = ui.shizuku.canControl && !ui.busy,
                                onClick = { vm.setDisplayMode(mode) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            enabled = ui.shizuku.canControl && !ui.busy,
                            onClick = { vm.setDisplayMode(mode) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when (mode) {
                                    MainViewModel.DisplayMode.EXTEND ->
                                        "外接屏作为独立屏幕，可单独设分辨率（改分辨率前建议先切到这个）"
                                    MainViewModel.DisplayMode.MIRROR ->
                                        "外接屏与平板显示相同内容（系统行为，本应用不强制切换）"
                                    MainViewModel.DisplayMode.EXTERNAL_ONLY ->
                                        "关闭平板内屏，只用外接屏（Wi-Fi / 蓝牙 / 键鼠继续工作）"
                                    MainViewModel.DisplayMode.UNKNOWN -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.refreshDisplayMode() },
                    enabled = !ui.busy,
                ) { Text("刷新显示模式状态") }
            }
            if (ui.displayModeDetail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    ui.displayModeDetail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 音频输出选择。
 *
 * 直接对应用户痛点：Android 默认把 USB-C / DP 显示器当音频输出，
 * 一线连之后蓝牙耳机就「没声」了。这里让用户显式指定声音从哪出。
 */
@Composable
private fun AudioOutputCard(vm: MainViewModel, ui: MainViewModel.UiState) {
    val protect by vm.preferInternalAudio.collectAsState(initial = true)
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("音频输出")
            Spacer(Modifier.height(4.dp))
            Text(
                "当前媒体音频：${ui.currentMediaOutput}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "当前通话音频：${ui.currentCommOutput}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------------- 声音被显示器抢走：直接给出修复入口 ----------------
            if (ui.audioStolenByDisplay) {
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "检测到声音正被显示器占用",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Android 会把 USB-C / DP 显示器当成音频输出设备" +
                                "（实测 ColorOS 把它报成「有线耳机」），" +
                                "所以一线连之后耳机/扬声器就没声了。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.fixAudioStolenByDisplay() },
                                enabled = !ui.busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    ui.suggestedAudioDevice?.let { "切到 ${it.typeName}" } ?: "切回平板侧",
                                )
                            }
                            OutlinedButton(
                                onClick = { vm.openSystemSoundSettings(context) },
                                enabled = !ui.busy,
                            ) { Text("系统设置") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (ui.audioOutputs.isEmpty()) {
                Text(
                    "读不到音频输出设备列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ui.audioOutputs.forEach { out ->
                val selected = ui.selectedAudioDeviceId == out.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, onClick = { vm.selectAudioDevice(out.id) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { vm.selectAudioDevice(out.id) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            out.displayName + if (out.isCurrentMedia) "（当前）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (out.isDisplayLike) {
                            Text(
                                "显示器类设备 —— 一线连时系统会优先往这里送声音",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.applyAudioOutput(pinMedia = true) },
                    enabled = !ui.busy && ui.selectedAudioDeviceId != null,
                    modifier = Modifier.weight(1f),
                ) { Text("设为音频输出") }
                OutlinedButton(
                    onClick = { vm.clearAudioOutput() },
                    enabled = !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("恢复自动") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "「设为音频输出」会把系统「媒体」音频固定到所选设备，一线连也能让声音留在耳机/扬声器。" +
                    "系统自带的媒体输出切换器（下拉通知栏 → 媒体输出）同样有效，" +
                    "但那是系统行为，本应用只是把它变成一键。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "接入外屏时自动把声音留在平板侧",
                subtitle = "优先蓝牙/有线耳机，其次平板扬声器；防止显示器抢走音频",
                checked = protect,
                onChange = { vm.setPreferInternalAudio(it) },
            )
            OutlinedButton(
                onClick = { vm.refreshAudio() },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("刷新音频状态") }
        }
    }
}

/** 分辨率 / 刷新率选择（任务书第 11 节的单选列表）。 */
@Composable
private fun ResolutionPicker(
    vm: MainViewModel,
    ui: MainViewModel.UiState,
    external: com.paddisplay.app.display.DisplaySnapshot,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("分辨率")
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = ui.selectedResolution == null,
                        onClick = { vm.selectAuto() },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.selectedResolution == null, onClick = { vm.selectAuto() })
                Text("自动 / 最佳（最高分辨率 + 60 Hz）", style = MaterialTheme.typography.bodyMedium)
            }

            val resolutions = external.distinctResolutions
            if (resolutions.isEmpty()) {
                Text(
                    "该外接屏未上报 supportedModes，无法列出可选分辨率",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            resolutions.forEach { pair ->
                val w = pair.first
                val h = pair.second
                val selected = ui.selectedResolution == pair
                // 标出"当前实际分辨率"，避免"点了没反应"的歧义
                val isCurrent = external.currentMode?.let { it.physicalWidth == w && it.physicalHeight == h } == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, onClick = { vm.selectResolution(w, h) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { vm.selectResolution(w, h) })
                    Text(
                        w.toString() + " × " + h.toString() +
                            if (isCurrent) "　← 当前" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            // 明确告知只有一种分辨率的情况（否则用户会以为功能坏了）
            if (resolutions.size <= 1) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (resolutions.isEmpty()) {
                        "该显示器没有上报任何 supportedModes。"
                    } else {
                        "该显示器只上报了 1 种分辨率 —— 没有别的可选，所以切换不会有变化。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            val res = ui.selectedResolution
            if (res != null) {
                val rates = external.refreshRatesFor(res.first, res.second)
                if (rates.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("刷新率")
                    Spacer(Modifier.height(4.dp))
                    rates.forEach { hz ->
                        val selected = ui.selectedRefresh?.let { kotlin.math.abs(it - hz) < 0.01f } == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, onClick = { vm.selectRefresh(hz) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = { vm.selectRefresh(hz) })
                            Text(
                                com.paddisplay.app.display.DisplayModeInfo.formatRefresh(hz) + " Hz" +
                                    if (kotlin.math.abs(hz - 60f) < 0.01f) "（推荐）" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "说明：真实 Mode 切换走 IDisplayManager.setUserPreferredDisplayMode；" +
                    "回退通道走 IWindowManager.setForcedDisplaySize(displayId, w, h)。" +
                    "两条通道都只作用于 displayId=" + external.displayId + "，不会修改系统默认分辨率。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 手动指定哪块是内屏 / 外屏（任务书第 3 节要求）。 */
@Composable
private fun RoleOverrideCard(vm: MainViewModel, ui: MainViewModel.UiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("手动指定屏幕角色")
            Spacer(Modifier.height(4.dp))
            Text(
                "如果自动判定不对（例如 ColorOS 上 displayId 不是 0），可以在这里修正。选择会被保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ui.displays.forEach { d ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "Display " + d.displayId + " — " + d.name + "（当前判定：" + d.role + "）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                vm.setRole(d.displayId, com.paddisplay.app.display.DisplayRole.INTERNAL)
                            },
                        ) { Text("设为内置屏") }
                        OutlinedButton(
                            onClick = {
                                vm.setRole(d.displayId, com.paddisplay.app.display.DisplayRole.EXTERNAL)
                            },
                        ) { Text("设为外接屏") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventLogCard(ui: MainViewModel.UiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("事件日志")
            Spacer(Modifier.height(6.dp))
            if (ui.logs.isEmpty()) {
                Text(
                    "（暂无事件）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ui.logs.takeLast(18).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ui.lastResult != null) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    ui.lastResult,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
