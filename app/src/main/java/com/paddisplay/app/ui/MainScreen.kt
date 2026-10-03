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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * MouseFlow 第一轮实验 —— 原生鼠标光标所在 Display。
 *
 * 任务书要求"先做实验、不要直接写完整功能"，本卡片即为此：
 * 只提供探测与两个强制切屏按钮，用来验证
 * "ColorOS + Shizuku 能否让系统原生鼠标从 Display 0 切到外屏"。
 *
 * 关键结论（AOSP 16 源码）：
 * NativeInputManagerService.setPointerDisplayId(int) 存在，但它是
 * native 本地接口、不是 Binder 服务，全 AOSP 只有一处调用 ——
 *   IMS.setDisplayViewportsInternal()
 *     -> mNative.setPointerDisplayId(mWindowManagerCallbacks.getPointerDisplayId())
 * 即 IMS 主动从 WMS 取值，App/Shizuku 都无从直接设置。
 *
 * 真正的开关是 WMS 的 InputManagerCallback.getPointerDisplayId() 读的两个
 * Global settings：桌面模式 + 自由窗口。
 */
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
internal fun AutomationCard(vm: MainViewModel) {
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
internal fun AudioOutputCard(vm: MainViewModel, ui: MainViewModel.UiState) {
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

/**
 * 黑边 / 镜像 诊断与修复。
 *
 * 这是用户实际遇到的核心问题：4K 显示器有黑边、分辨率设不上、
 * 只能用镜像复制模式。根因通常是外接屏**仍在被镜像** ——
 * 镜像时它跟着内屏的模式走，于是 4K 设不上、画面被放大后出现黑边。
 */
@Composable
internal fun ResolutionPicker(
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
internal fun RoleOverrideCard(vm: MainViewModel, ui: MainViewModel.UiState) {
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
internal fun EventLogCard(ui: MainViewModel.UiState) {
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
