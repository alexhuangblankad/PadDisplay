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

            // ============================================================
            // 主操作区 —— 只需要看这两个按钮
            // ============================================================
            item { PrimaryActionsCard(vm = vm, ui = ui) }

            // ============================================================
            // 以下全部收进「高级选项」，默认收起
            // ============================================================
            // MouseFlow 第一轮实验：原生鼠标光标所在屏
            item { MouseFlowProbeCard(vm = vm, ui = ui) }

            // 桌面模式（DeX / TNT 类桌面）：这是"要一个真正的桌面"的正解
            item { DesktopModeCard(vm = vm, ui = ui) }

            // 触控板：解决「鼠标不能跨屏」的实际替代方案
            item { TouchpadEntryCard(ui = ui) }

            // 应用抽屉 —— 外接屏的「开始菜单」，这是你要的"桌面"
            item { AppDrawerCard(vm = vm, ui = ui) }

            item { AdvancedToggleRow(vm = vm, ui = ui) }

            if (ui.advancedExpanded) {
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
                    item { MirrorFixCard(vm = vm, ui = ui, external = external) }
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
private fun MouseFlowProbeCard(vm: MainViewModel, ui: MainViewModel.UiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.22f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "MouseFlow 实验：原生鼠标光标在哪块屏",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "先探测、再动手。第一步只搞清楚 ColorOS 把原生光标放在哪、为什么。" +
                    "下面的按钮用注入方式移动指针位置，验证注入这条路是否可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.probePointerDisplay() },
                enabled = ui.shizuku.canControl && !ui.busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text("探测原生指针所在屏（先点这个）") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.forcePointer(MainViewModel.DesktopTarget.INTERNAL) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("强制鼠标到内屏") }
                OutlinedButton(
                    onClick = { vm.forcePointer(MainViewModel.DesktopTarget.EXTERNAL) },
                    enabled = ui.shizuku.canControl && !ui.busy && ui.externalConnected,
                    modifier = Modifier.weight(1f),
                ) { Text("强制鼠标到外屏") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "注：这两个按钮移动的是指针位置（注入）。「光标归属哪块屏」由系统按" +
                    "桌面模式 + 自由窗口自动决定，不是这里能直接设的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 桌面模式（DeX / TNT 类桌面）—— 「想要一个真正的桌面」的正解。
 *
 * 依据：脚本项目 fox0001/android-desktop-mode 的 README 指出，
 * 开发者选项里勾选「启用可自由调整的窗口」+「强制使用桌面模式」，
 * 即可启用系统内置的桌面模式（App 可自由拖动、调整窗口大小）。
 *
 * 而这两个勾就是两个 Global settings；AOSP WMS 的 SettingsObserver 直接监听它们，
 * 用 shell 身份（WRITE_SECURE_SETTINGS）写入即可，不需要 root。
 *
 * 注意：AOSP 自己在 WMS 里对桌面模式的注释是
 *   "TODO: Show mouse pointer on external screen."
 * 即桌面模式并不解决「鼠标指针显示在外接屏」，更不解决「指针跨两块屏」。
 */
@Composable
private fun DesktopModeCard(vm: MainViewModel, ui: MainViewModel.UiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "桌面模式（DeX / 锤子 TNT 类桌面）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Android 10 起系统内置「桌面模式」：App 可自由拖动位置、调整窗口大小，跟 PC 一样。" +
                    "它的开关就是开发者选项里那两个勾，对应两个 Global 设置 —— " +
                    "用 Shizuku 的 shell 身份可以直接写，不需要 root。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "写入后通常需要重新插拔外接屏或重启才会完全生效。" +
                    "注意：AOSP 自己在代码里仍留着「TODO: 在外接屏显示鼠标指针」，" +
                    "所以桌面模式不解决鼠标跨屏问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.enableDesktopMode() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("开启桌面模式") }
                OutlinedButton(
                    onClick = { vm.disableDesktopMode() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("关闭") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.probeDesktopMode() },
                enabled = ui.shizuku.canControl && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("探测桌面模式能力（先看这个）") }
        }
    }
}

/**
 * 触控板入口 —— 「鼠标不能跨屏」的实际替代方案。
 *
 * 鼠标自由跨屏依赖 DisplayTopology，而本机该 feature flag 被 ROM 关闭，
 * 非 root 打不开。所以改为：用平板当外接屏的触控板（自绘光标 + 注入指针）。
 */
@Composable
private fun TouchpadEntryCard(ui: MainViewModel.UiState) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "用平板当外接屏的触控板（⚠️ 不推荐，会破坏 Moonlight 鼠标）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "鼠标自由跨两块屏依赖系统的 DisplayTopology，而本机该能力被 ROM 关闭" +
                    "（探测结果是 supported=false），非 root 打不开。\n\n" +
                    "所以换一条已跑通的路：在外接屏上自绘一个光标（系统只给真实鼠标画指针），" +
                    "再用注入的方式把你的滑动与点击送进外接屏。\n" +
                    "效果：手指在平板上滑 -> 外接屏上的光标跟着走 -> 轻点就是单击。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { com.paddisplay.app.touchpad.TouchpadActivity.start(context) },
                enabled = ui.shizuku.canControl,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("打开触控板") }
            if (!ui.shizuku.canControl) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要 Shizuku（注入指针要用 shell 身份）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * 主操作区 —— 界面上只需要看这两个按钮。
 *
 * ① 一键开始 ＝ 你想要的最终效果；② 一键还原 ＝ 出问题时的救命按钮。
 */
@Composable
private fun PrimaryActionsCard(vm: MainViewModel, ui: MainViewModel.UiState) {
    var confirmRestore by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("① 一键开始", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "按顺序自动完成：外接屏独立显示（去掉黑边）→ 平板内屏保持点亮 → " +
                    "声音留在平板（耳机/扬声器）→ 在外接屏打开应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.oneClickExtend() },
                enabled = ui.shizuku.canControl && !ui.busy && ui.externalConnected,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    if (!ui.externalConnected) "未检测到外接屏" else "一键开始（外接屏独立显示）",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (!ui.shizuku.canControl) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要先授权 Shizuku（见上方「系统权限」）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Text("② 一键还原（出问题时点这个）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "把本应用改动过的所有系统状态恢复原样：内屏电源与窗口模式、输入设备关联、" +
                    "音频输出固定、显示拓扑、尺寸覆盖。顺序上先点亮内屏，所以即使你正黑屏也能救回来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { confirmRestore = true },
                enabled = !ui.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("一键还原所有设置", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "若 Shizuku 已断开导致还原无效：重启设备一定能恢复（系统级状态都不落盘）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmRestore) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("确认还原所有设置？") },
            text = {
                Text(
                    "会解除输入绑定、清除音频固定、还原显示拓扑与尺寸覆盖、" +
                        "并把内屏恢复为点亮状态。\n\n这是安全的「恢复默认」，不会影响你的其他应用与数据。",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    vm.restoreAll()
                }) { Text("确认还原") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRestore = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 应用抽屉 —— 外接屏的「开始菜单」。
 *
 * 为什么需要它：早先启动应用时**写死了 `com.android.settings/.Settings`**，
 * 所以外接屏上只能打开「设置」。要开别的应用必须先解析它们的 launcher 组件，
 * 这里就是把整份应用清单列出来，让你点一下就能开到外接屏。
 */
@Composable
private fun AppDrawerCard(vm: MainViewModel, ui: MainViewModel.UiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.toggleAppDrawer() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ui.appDrawerExpanded) "▼ 打开应用到外接屏" else "▶ 打开应用到外接屏（点开应用列表）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "相当于外接屏的「开始菜单」：点应用名就把它开在外接屏上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (ui.appDrawerExpanded) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.refreshLaunchableApps() },
                        enabled = ui.shizuku.canControl && !ui.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("刷新列表（${ui.launchableApps.size}）") }
                }
                Spacer(Modifier.height(8.dp))

                if (!ui.shizuku.canControl) {
                    Text(
                        "需要 Shizuku 才能把应用启动到外接屏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (ui.launchableApps.isEmpty()) {
                    Text(
                        "还没有应用列表，点上面的「刷新列表」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ui.launchableApps.take(40).forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { vm.launchAppOnExternal(app) },
                            enabled = !ui.busy && ui.externalConnected,
                        ) { Text("外屏") }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = { vm.launchAppOnInternal(app) },
                            enabled = !ui.busy,
                        ) { Text("内屏") }
                    }
                }
                if (ui.launchableApps.size > 40) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "… 另有 ${ui.launchableApps.size - 40} 个应用未显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 「高级选项」折叠开关 —— 默认收起，避免一屏塞满按钮。 */
@Composable
private fun AdvancedToggleRow(vm: MainViewModel, ui: MainViewModel.UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { vm.toggleAdvanced() }
                .padding(14.dp),
        ) {
            Text(
                if (ui.advancedExpanded) "▼ 高级选项（已展开）" else "▶ 高级选项（点开查看更多）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "显示器详情、分辨率、音频输出、显示模式、输入绑定、屏幕位置、坐标空间排查" +
                    " —— 平时不需要动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/**
 * 黑边 / 镜像 诊断与修复。
 *
 * 这是用户实际遇到的核心问题：4K 显示器有黑边、分辨率设不上、
 * 只能用镜像复制模式。根因通常是外接屏**仍在被镜像** ——
 * 镜像时它跟着内屏的模式走，于是 4K 设不上、画面被放大后出现黑边。
 */
@Composable
private fun MirrorFixCard(
    vm: MainViewModel,
    ui: MainViewModel.UiState,
    external: com.paddisplay.app.display.DisplaySnapshot,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "黑边 / 分辨率设不上？",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "如果你的 4K 显示器出现黑边、并且只能用镜像复制模式，" +
                    "最常见的原因是**外接屏仍在被系统镜像**：" +
                    "镜像时它会跟着平板内屏的分辨率走，4K 自然设不上，" +
                    "画面被放大到 4K 面板后周围就是黑边。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "注意：界面显示的「扩展」只代表 windowingMode=FULLSCREEN，" +
                    "**它不能说明有没有在镜像**，所以要单独探测。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.runMirrorProbe() },
                enabled = ui.shizuku.canControl && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("① 探测镜像状态（先做这个）") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.disableForcedDesktopMode() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("② 关闭强制桌面模式") }
                OutlinedButton(
                    onClick = { vm.enableForcedDesktopMode() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("恢复默认") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "②写的是 Android 的全局设置 " +
                    "development_force_desktop_mode_on_external_displays = 0。" +
                    "它是 AOSP 让外接屏被强制镜像的条件之一。改完请**重新插拔外接屏**" +
                    "让显示策略重算，然后再点①看镜像是否解除、分辨率列表是否出现更多选项。" +
                    "「恢复默认」把它写回 1。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.runModeSelfCheck() },
                enabled = ui.shizuku.canControl && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("③ Mode 自检（看硬件上报了哪些模式）") }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "决定性实验（系统自带手段，绕开本应用）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "用系统自己的 `am start --display` 在外接屏上开一个窗口。" +
                    "如果外接屏上出现填满 4K 的独立窗口 → 说明「扩展」在系统层面成立，" +
                    "黑边来自上层的投屏/镜像，需要在 ColorOS 的多屏设置里关闭；" +
                    "如果仍然带黑边 → 说明 ColorOS 的多屏服务在更上层接管了外接屏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.launchTestWindowOnExternal() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("④ 外屏开测试窗口") }
                OutlinedButton(
                    onClick = { vm.findColorOsDisplaySettings() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("找 ColorOS 多屏设置") }
            }

            // ---------------- 输入绑定：扩展模式可操作的关键 ----------------
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "⑤ 把输入绑定到外接屏（扩展模式能不能用的关键）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "实测结论：外接屏**能**独立满屏 4K 渲染，但 Android 默认**不把输入设备" +
                    "绑定到外接屏** —— 所以扩展模式下鼠标到不了外屏、也操作不了，" +
                    "系统才用「复制模式」回避了这个坑。\n\n" +
                    "绑定成功后，鼠标/触摸就能作用到外接屏，你才真正拥有无黑边的扩展桌面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.bindInputToExternal() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("绑定输入到外屏") }
                OutlinedButton(
                    onClick = { vm.clearInputBindings() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("解除绑定") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.listInputDevices() },
                enabled = ui.shizuku.canControl && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看输入设备列表") }

            // ---------------- 一键双桌面 ----------------
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "⑥ 两个桌面（当前阶段目标）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "把内屏和外屏当作两个独立桌面：分别在不同屏幕启动应用、各自运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.setupDualDesktop() },
                enabled = ui.shizuku.canControl && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("一键建立双桌面（扩展 + 绑输入 + 设左右）") }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.launchOnDesktop(MainViewModel.DesktopTarget.INTERNAL) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("在内屏开应用") }
                OutlinedButton(
                    onClick = { vm.launchOnDesktop(MainViewModel.DesktopTarget.EXTERNAL) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("在外屏开应用") }
            }

            // ---------------- 屏幕左右关系 ----------------
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "⑦ 屏幕左右关系（Android 13+ 的显示拓扑）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "AOSP 在 feature flag 打开时会把显示拓扑**喂给输入系统**" +
                    "（DisplayManagerService → mInputManagerInternal.setDisplayTopology），" +
                    "这正是 Android 里「光标跨屏」的机制。\n\n" +
                    "⚠️ flag 关闭时服务端 `setDisplayTopology` 是**静默空操作**，" +
                    "所以必须先探测、并读回验证 —— 我不会盲报成功。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.probeTopology() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("探测是否支持") }
                OutlinedButton(
                    onClick = { vm.setScreenLayout(position = 0) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("在左") }
                OutlinedButton(
                    onClick = { vm.setScreenLayout(position = 1) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("在上") }
                OutlinedButton(
                    onClick = { vm.setScreenLayout(position = 2) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("在右") }
                OutlinedButton(
                    onClick = { vm.setScreenLayout(position = 3) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("在下") }
            }

            // ---------------- 光标错位 / 坐标空间 ----------------
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "⑧ 光标错位 / 坐标空间排查",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "参考项目 AdaptiveScreenPlus 的实测结论：`Display.getRealSize()` / " +
                    "`getMetrics()` 会被本应用的「兼容缩放」污染 —— " +
                    "内屏实际 1920×1080 被报成 1496×1242，于是光标被夹在 x≤1495、" +
                    "右侧 424px 永远够不到，表现就是**光标与点击位置错位**。\n\n" +
                    "唯一可靠的注入坐标空间是 `dumpsys window displays` 里那块屏的 `cur=WxH`，" +
                    "它与 `input -d N` / screencap 同一套坐标系。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.probeCoordinateSpaces() },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("⑧ 对照坐标空间") }
                OutlinedButton(
                    onClick = { vm.testInjectTap(MainViewModel.DesktopTarget.EXTERNAL) },
                    enabled = ui.shizuku.canControl && !ui.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("外屏注入点击") }
            }
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
