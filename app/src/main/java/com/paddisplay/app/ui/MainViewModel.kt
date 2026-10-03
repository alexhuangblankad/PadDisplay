package com.paddisplay.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paddisplay.app.automation.DisplayHotplugManager
import com.paddisplay.app.data.SettingsRepository
import com.paddisplay.app.display.DisplayModeInfo
import com.paddisplay.app.display.DisplayModeSelector
import com.paddisplay.app.display.DisplayRole
import com.paddisplay.app.display.DisplaySnapshot
import com.paddisplay.app.shizuku.ShizukuManager
import com.paddisplay.app.system.AudioRoutingController
import com.paddisplay.app.system.SystemDisplayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主界面状态管理。
 *
 * 设计上刻意保持“一个屏幕、一组开关”，不做多层设置页（任务书第 11 节）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    val systemService = SystemDisplayService(app, settings)
    val hotplug = DisplayHotplugManager(app, settings, systemService)

    // ------------------------------------------------------------------
    // UI 状态
    // ------------------------------------------------------------------

    data class UiState(
        val displays: List<DisplaySnapshot> = emptyList(),
        val shizuku: ShizukuManager.ConnectionState = ShizukuManager.state.value,
        val logs: List<String> = emptyList(),
        val busy: Boolean = false,
        val lastResult: String? = null,
        val diagnostics: String = "",
        val showDiagnostics: Boolean = false,
        val externalConnected: Boolean = false,
        /** 关屏/切分辨率前的确认倒计时（防黑屏）。null 表示没有待确认操作。 */
        val pendingConfirm: PendingConfirm? = null,
        /** 用户选择：null 表示“自动/最佳”。 */
        val selectedMode: DisplayModeInfo? = null,
        val selectedResolution: Pair<Int, Int>? = null,
        val selectedRefresh: Float? = null,
        val internalTurnedOff: Boolean = false,
        // ---------------- 音频 ----------------
        val audioOutputs: List<AudioRoutingController.AudioOutput> = emptyList(),
        val selectedAudioDeviceId: Int? = null,
        val currentMediaOutput: String = "(读不到)",
        val currentCommOutput: String = "(读不到)",
        /** 声音是否正被「显示器类」设备抢走（实测 ColorOS 把显示器报成耳机） */
        val audioStolenByDisplay: Boolean = false,
        /** 被抢走时建议切回的设备 */
        val suggestedAudioDevice: AudioRoutingController.AudioOutput? = null,
        // ---------------- 显示模式 ----------------
        val displayMode: DisplayMode = DisplayMode.UNKNOWN,
        val displayModeDetail: String = "",
    )

    /** 外接屏显示模式（对标 Windows Win+P）。 */
    enum class DisplayMode(val label: String) {
        EXTEND("扩展"),
        MIRROR("复制"),
        EXTERNAL_ONLY("仅外接屏"),
        UNKNOWN("未知"),
    }

    /** 待确认的危险操作（防黑屏回滚）。 */
    data class PendingConfirm(
        val title: String,
        val detail: String,
        val secondsLeft: Int,
        /** 用户确认后执行的“保留”动作。 */
        val onKeep: suspend () -> Unit,
        /** 超时/取消时的“回滚”动作。 */
        val onRollback: suspend () -> Unit,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        ShizukuManager.init(app)
        observeShizuku()
        observeHotplug()
        refreshDisplays()
        hotplug.start()

        // ================================================================
        // 音频状态自愈（非常重要，防止「卸载后声音还是坏的」）
        //
        // 音频的「首选设备」偏好是写在系统 AudioService 策略状态里的，
        // 它会比 App 活得更久 —— 用户卸载 App 也不会自动清掉，只有重启才恢复。
        // 这既有可能是用户现在遇到的音频异常的成因，也是我们必须避免的副作用。
        //
        // 因此采取「不跨会话残留」策略：App 每次启动都清除本应用可能设置过的
        // 音频偏好，交回系统自动路由。
        //
        // ⚠️ 审计 F8：这里必须**带重试**。早先版本只 delay(2500) 试一次，
        // 而 Shizuku 授权通常需要用户手动操作，2.5 秒内根本连不上，
        // 于是清除永远没执行 —— 那就等于没有任何保障。
        // 现在与内屏自检一样：循环等待 Shizuku 就绪，直到成功或次数用尽。
        // ================================================================
        viewModelScope.launch {
            for (attempt in 1..12) {
                delay(if (attempt == 1) 1500 else 1500)
                if (!ShizukuManager.state.value.canControl) continue

                appendLog("启动自愈（第 $attempt 次）：清除上一次会话可能留下的音频输出固定")
                val r = systemService.clearAudioOutputPreference()
                if (r.ok) {
                    appendLog("启动自愈成功：系统音频已交回自动路由")
                    refreshAudio()
                    return@launch
                }
                appendLog("启动自愈未成功：${r.toText().replace("\n", " / ")}")
            }
            appendLog("警告：启动自愈未能清除音频固定（Shizuku 可能一直未就绪）")
        }

        // 崩溃/异常退出后的安全兜底。
        //
        // 场景：上一次会话把内屏关掉了，之后 App 进程被 ColorOS 杀掉，
        // 用户又拔掉了外接屏 —— 这时没有任何监听器还活着。
        // 所以只要「上次记录为内屏已关」或「当前没有外接屏」，就必须尝试点亮内屏，
        // 并且要等 Shizuku 连上后重试若干次，而不是只试一次就放弃。
        viewModelScope.launch {
            val wasOff = systemService.wasInternalScreenOff()
            for (attempt in 1..10) {
                delay(if (attempt == 1) 1200 else 1500)
                if (!ShizukuManager.state.value.canControl) continue

                val externalAlive = runCatching { systemService.externalDisplays().isNotEmpty() }
                    .getOrDefault(false)

                if (wasOff || !externalAlive) {
                    appendLog(
                        "启动自检（第 $attempt 次）：上次内屏状态=已关，外接屏=" +
                            (if (externalAlive) "有" else "无") + "，尝试恢复内屏",
                    )
                    val r = systemService.restoreInternalDisplayWithRetry(attempts = 2)
                    if (r.ok) {
                        appendLog("启动自检：内屏已恢复")
                        _ui.value = _ui.value.copy(internalTurnedOff = false)
                        return@launch
                    }
                } else {
                    // 内屏状态正常且外接屏在，无需处理
                    return@launch
                }
            }
            appendLog("警告：启动自检未能恢复内屏，请手动点「重新打开平板屏幕」")
        }
    }

    private fun observeShizuku() {
        viewModelScope.launch {
            ShizukuManager.state.collect { st ->
                _ui.value = _ui.value.copy(shizuku = st)
                appendLog("Shizuku: ${st.stage} - ${st.message}")
            }
        }
        // 每一次 UserService 连接建立，都做一遍音频状态自愈。
        // 授权通常发生在 App 启动之后，只靠启动时清一次是不够的。
        viewModelScope.launch {
            ShizukuManager.connectEpoch.collect { epoch ->
                if (epoch <= 0) return@collect
                appendLog("检测到 Shizuku 新连接（第 $epoch 次），清除可能残留的音频固定")
                val r = systemService.clearAudioOutputPreference()
                appendLog(if (r.ok) "音频自愈成功" else "音频自愈未成功：${r.title}")
                refreshAudio()
            }
        }
    }

    private fun observeHotplug() {
        viewModelScope.launch {
            hotplug.events.collect { evts ->
                _ui.value = _ui.value.copy(logs = evts)
            }
        }
        viewModelScope.launch {
            hotplug.externalConnected.collect { connected ->
                _ui.value = _ui.value.copy(externalConnected = connected)
            }
        }
        // 内屏关闭状态以持久化值为单一来源，避免自动路径与手动路径各写一份导致 UI 显示不一致
        viewModelScope.launch {
            settings.internalScreenOff.collect { off ->
                _ui.value = _ui.value.copy(internalTurnedOff = off)
            }
        }
    }

    // ------------------------------------------------------------------
    // 枚举
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 分辨率 / Mode 自检
    // ------------------------------------------------------------------

    /** 一键采集"当前 Mode / 可选 Mode / 首选 Mode / shell 环境"，用于定位分辨率问题。 */
    fun runModeSelfCheck() {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器，无法自检")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val sb = StringBuilder()
            sb.appendLine("=========== Mode 自检 ===========")
            sb.appendLine("外接屏 displayId = ${external.displayId}（${external.name}）")
            sb.appendLine("App 侧看到的当前 Mode = ${external.currentModeLabel}")
            sb.appendLine("App 侧看到的 supportedModes 数量 = ${external.supportedModes.size}")
            sb.appendLine("App 侧可选分辨率 = " + external.distinctResolutions.joinToString { "${it.first}×${it.second}" })
            sb.appendLine("物理屏 ID = ${external.physicalDisplayId}，token 可用 = ${external.physicalTokenAvailable}")
            sb.appendLine()
            sb.appendLine("--- UserService 侧读回 ---")
            sb.appendLine(systemService.modeState(external.displayId))
            sb.appendLine()
            sb.appendLine("--- shell 环境 ---")
            sb.appendLine(systemService.probeShellEnvironment())
            sb.appendLine()
            sb.appendLine("--- 当前逻辑尺寸 ---")
            sb.appendLine(runCatching { systemService.execCommand("wm size -d ${external.displayId}") }.getOrElse { "失败: ${it.message}" })
            _ui.value = _ui.value.copy(
                busy = false,
                diagnostics = sb.toString(),
                showDiagnostics = true,
            )
            appendLog("已生成 Mode 自检报告")
        }
    }

    /**
     * 镜像 / 黑边诊断：判断外接屏是否仍在被镜像。
     *
     * 这是「4K 显示器有黑边、分辨率设不上」的根因排查：
     * `windowingMode == FULLSCREEN` **不代表**没在镜像。
     */
    fun runMirrorProbe() {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器，无法探测")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val sb = StringBuilder()
            sb.appendLine("=========== 黑边 / 镜像 诊断 ===========")
            sb.appendLine("外接屏 displayId = ${external.displayId}（${external.name}）")
            sb.appendLine("App 侧当前 Mode = ${external.currentModeLabel}")
            sb.appendLine("App 侧 supportedModes 数量 = ${external.supportedModes.size}")
            sb.appendLine()
            sb.appendLine(systemService.probeMirrorState(external.displayId))
            _ui.value = _ui.value.copy(busy = false, diagnostics = sb.toString(), showDiagnostics = true)
            appendLog("已生成镜像/黑边诊断报告")
        }
    }

    /** 关闭「外接屏强制桌面模式」—— 打破镜像、让外接屏能独立设 4K。 */
    fun disableForcedDesktopMode() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.setForceDesktopMode(enable = false)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("关闭强制桌面模式：${r.toText().replace("\n", " / ")}")
            delay(500)
            runMirrorProbe()
        }
    }

    /** 恢复「外接屏强制桌面模式」到系统默认（1）。 */
    fun enableForcedDesktopMode() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.setForceDesktopMode(enable = true)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("恢复强制桌面模式：${r.toText().replace("\n", " / ")}")
        }
    }

    /**
     * 决定性实验：用系统自带 `am start --display` 在外接屏上开一个窗口。
     *
     * 这是判断"外接屏能否真正独立渲染"的唯一可靠办法：
     * 绕开本应用的所有实现，看系统自己能不能在外屏上开出满屏窗口。
     */
    fun launchTestWindowOnExternal() {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            // 用「设置」作为测试窗口，一定存在且容易辨认
            val r = systemService.launchOnDisplay(external.displayId, "com.android.settings/.Settings")
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("独立渲染测试：${r.toText().replace("\n", " / ")}")
        }
    }

    /** 找 ColorOS 多屏/投屏设置入口（外接屏行为由它控制）。 */
    fun findColorOsDisplaySettings() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val text = systemService.findDisplaySettingsActivities()
            _ui.value = _ui.value.copy(
                busy = false,
                diagnostics = "=========== ColorOS 多屏设置入口 ===========\n\n$text",
                showDiagnostics = true,
            )
            appendLog("已列出 ColorOS 多屏相关设置入口")
        }
    }

    /**
     * 把外接输入设备（触摸/鼠标/键盘）绑定到外接屏。
     *
     * 这是「扩展模式能用」的关键一步：Android 默认不把输入绑到外接屏，
     * 所以扩展模式下能渲染但鼠标/触摸到不了外屏 —— 系统正是因此才用「复制模式」。
     */
    fun bindInputToExternal() {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.bindInputToDisplay(external.displayId)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("绑定输入到外接屏：${r.toText().replace("\n", " / ")}")
        }
    }

    /** 解除输入绑定，恢复默认。 */
    fun clearInputBindings() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.clearInputAssociations()
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("解除输入绑定：${r.toText().replace("\n", " / ")}")
        }
    }

    /** 列出输入设备（用来确认哪些设备是可绑定的外接设备）。 */
    fun listInputDevices() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val text = systemService.listInputDevices()
            _ui.value = _ui.value.copy(
                busy = false,
                diagnostics = "=========== 输入设备 ===========\n\n$text",
                showDiagnostics = true,
            )
            appendLog("已列出输入设备")
        }
    }

    fun refreshDisplays() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val overrides = settings.roleOverrides.first()
            // 枚举会走反射 + Binder，放 IO 线程（审计 F11）
            val displays = systemService.enumerateDisplays(overrides)
            _ui.value = _ui.value.copy(
                displays = displays,
                externalConnected = displays.any { d -> d.isExternal },
            )
            refreshAudio()
            refreshDisplayMode()
        }
    }

    // ------------------------------------------------------------------
    // 音频输出
    // ------------------------------------------------------------------

    /** 刷新音频输出列表与当前实际路由（公共 API，不需要 Shizuku）。 */
    fun refreshAudio() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val outputs = systemService.audio.availableOutputs()
            val snapshot = systemService.audio.routingSnapshot()
            val media = systemService.audio.currentMediaOutput()
            val comm = systemService.audio.currentCommunicationOutput()
            val mediaLabel = media?.let { "${AudioRoutingController.typeLabel(it.type)}" } ?: "(读不到)"
            _ui.value = _ui.value.copy(
                audioOutputs = outputs,
                currentMediaOutput = mediaLabel,
                currentCommOutput = comm,
                audioStolenByDisplay = snapshot.stolenByDisplay,
                suggestedAudioDevice = snapshot.suggested,
                // 默认选中「当前媒体输出」，方便直接把声音钉回耳机
                selectedAudioDeviceId = _ui.value.selectedAudioDeviceId
                    ?: outputs.firstOrNull { it.isCurrentMedia }?.id
                    ?: outputs.firstOrNull { !it.isDisplayLike }?.id,
            )
            appendLog("音频输出：当前媒体=$mediaLabel，通话=$comm，可选设备 ${outputs.size} 个")
            if (snapshot.stolenByDisplay) {
                appendLog(
                    "⚠️ 检测到声音正被显示器类设备「${snapshot.current?.displayName}」占用" +
                        (snapshot.suggested?.let { "，建议切到「${it.displayName}」" } ?: ""),
                )
            }
        }
    }

    /**
     * 一键把声音从显示器拉回平板侧（蓝牙/有线耳机，其次内置扬声器）。
     *
     * 这是实测确认的痛点：Android 把 USB-C/DP 显示器当音频输出设备，
     * 一线连之后耳机就没声了。系统自带媒体输出切换能修，但要点好几层；
     * 这里用一个按钮直接修好。
     */
    fun fixAudioStolenByDisplay() {
        viewModelScope.launch {
            val target = _ui.value.suggestedAudioDevice
                ?: _ui.value.audioOutputs.firstOrNull { !it.isDisplayLike }
            if (target == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 找不到可以切回的输出设备")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true, selectedAudioDeviceId = target.id)
            val r = systemService.setAudioOutputDevice(target.id, pinMedia = true, pinComm = false)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("把声音从显示器切到「${target.displayName}」：${r.toText().replace("\n", " / ")}")
            // 记住这个选择，下次接入外屏时优先用它
            settings.setPreferredAudioDeviceId(target.id)
            delay(700)
            refreshAudio()
        }
    }

    /** 打开系统「声音」设置页（系统媒体输出切换器在这里）。 */
    fun openSystemSoundSettings(context: android.content.Context) {
        runCatching {
            val intent = android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            appendLog("已打开系统声音设置（可在其中切换媒体输出设备）")
        }.onFailure {
            appendLog("打开系统声音设置失败：${it.message}")
            _ui.value = _ui.value.copy(
                lastResult = "无法打开系统声音设置，请手动下拉通知栏 → 点「媒体输出」切换",
            )
        }
    }

    fun selectAudioDevice(deviceId: Int) {
        _ui.value = _ui.value.copy(selectedAudioDeviceId = deviceId)
    }

    /**
     * 把音频输出切到选定设备。
     * @param pinMedia 固定媒体音频（需要 Shizuku）
     */
    fun applyAudioOutput(pinMedia: Boolean = true) {
        viewModelScope.launch {
            val id = _ui.value.selectedAudioDeviceId
            if (id == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 请先选择一个音频输出设备")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.setAudioOutputDevice(id, pinMedia = pinMedia, pinComm = false)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("切换音频输出 -> deviceId=$id: ${r.toText().replace("\n", " / ")}")
            delay(600)
            refreshAudio()
        }
    }

    /** 恢复系统自动音频路由（一键「别乱动我的声音」）。 */
    fun clearAudioOutput() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.clearAudioOutputPreference()
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("清除音频输出偏好: ${r.toText().replace("\n", " / ")}")
            delay(600)
            refreshAudio()
        }
    }

    val preferInternalAudio by lazy { settings.preferInternalAudioOnExternal }

    fun setPreferInternalAudio(value: Boolean) = viewModelScope.launch {
        settings.setPreferInternalAudioOnExternal(value)
        appendLog(if (value) "已开启：接入外屏时自动把声音留在平板（耳机/扬声器）" else "已关闭外屏音频保护")
    }

    // ------------------------------------------------------------------
    // 外接屏显示模式（扩展 / 复制 / 仅外接屏）
    // ------------------------------------------------------------------

    fun refreshDisplayMode() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(displayMode = DisplayMode.UNKNOWN, displayModeDetail = "未检测到外接屏")
                return@launch
            }
            val detail = systemService.displayModeState(external.displayId)
            // 审计 F15：优先用**真实的 windowingMode** 判定，而不是拿"内外屏尺寸相同"
            // 去猜镜像（外屏恰好和内屏同分辨率时会被误判成复制）。
            val realWindowingMode = systemService.externalWindowingMode(external.displayId)
            val mode = when {
                _ui.value.internalTurnedOff -> DisplayMode.EXTERNAL_ONLY
                realWindowingMode == com.paddisplay.app.system.DisplayMirrorController.WINDOWING_MODE_FULLSCREEN ->
                    DisplayMode.EXTEND
                // 读不到 windowingMode 时，才退回尺寸启发式
                realWindowingMode == null && systemService.looksMirrored() -> DisplayMode.MIRROR
                realWindowingMode == null -> DisplayMode.EXTEND
                else -> DisplayMode.EXTEND
            }
            _ui.value = _ui.value.copy(displayMode = mode, displayModeDetail = detail)
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true, displayMode = mode)
            val lines = mutableListOf<String>()
            when (mode) {
                DisplayMode.EXTEND -> {
                    val r = systemService.applyExtendMode(external.displayId)
                    lines += r.toText()
                    // 扩展模式下内屏要保持点亮
                    if (_ui.value.internalTurnedOff) {
                        val on = systemService.restoreInternalDisplayWithRetry(attempts = 2)
                        hotplug.markInternalTurnedOff(!on.ok)
                        lines += offOrOnResult(on)
                    }
                }
                DisplayMode.MIRROR -> {
                    lines += "复制模式为系统行为：Android 14+ 已移除强制镜像 API。"
                    lines += "当前状态以系统读回为准，详见下方详情。"
                }
                DisplayMode.EXTERNAL_ONLY -> {
                    val off = systemService.turnOffInternalDisplay()
                    hotplug.markInternalTurnedOff(off.ok)
                    lines += offOrOnResult(off)
                }
                DisplayMode.UNKNOWN -> Unit
            }
            _ui.value = _ui.value.copy(busy = false, lastResult = lines.joinToString("\n"))
            appendLog("显示模式 -> ${mode.label}: " + lines.joinToString(" / ").replace("\n", " "))
            delay(500)
            refreshDisplays()
        }
    }

    private fun offOrOnResult(r: SystemDisplayService.OpResult): String =
        (if (r.ok) "✅ " else "❌ ") + r.title

    fun appendLog(msg: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _ui.value = _ui.value.copy(logs = (_ui.value.logs + "[$stamp] $msg").takeLast(80))
    }

    // ------------------------------------------------------------------
    // Shizuku
    // ------------------------------------------------------------------

    fun requestShizuku() = ShizukuManager.requestPermission()

    fun retryShizuku() {
        ShizukuManager.refresh()
        appendLog("重新检测 Shizuku…")
    }

    fun openShizukuApp() = ShizukuManager.openShizukuApp()

    // ------------------------------------------------------------------
    // 内屏开关
    // ------------------------------------------------------------------

    fun turnOffInternal() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val result = systemService.turnOffInternalDisplay()
            // 只有真的成功才记录「已关」，否则会误导 failsafe 与 UI
            hotplug.markInternalTurnedOff(result.ok)
            _ui.value = _ui.value.copy(
                busy = false,
                lastResult = result.toText(),
                internalTurnedOff = result.ok,
            )
            appendLog(result.toText().replace("\n", " / "))
        }
    }

    fun turnOnInternal() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val result = systemService.restoreInternalDisplayWithRetry(attempts = 3)
            hotplug.markInternalTurnedOff(!result.ok)
            _ui.value = _ui.value.copy(
                busy = false,
                lastResult = result.toText(),
                internalTurnedOff = !result.ok,
            )
            appendLog(result.toText().replace("\n", " / "))
        }
    }

    // ------------------------------------------------------------------
    // 外屏分辨率
    // ------------------------------------------------------------------

    fun selectResolution(width: Int, height: Int) {
        val external = systemService.primaryExternal() ?: return
        val rates = external.refreshRatesFor(width, height)
        _ui.value = _ui.value.copy(
            selectedResolution = width to height,
            selectedRefresh = rates.minByOrNull { kotlin.math.abs(it - 60f) },
            selectedMode = null,
        )
    }

    fun selectRefresh(hz: Float) {
        _ui.value = _ui.value.copy(selectedRefresh = hz, selectedMode = null)
    }

    fun selectAuto() {
        _ui.value = _ui.value.copy(selectedMode = null, selectedResolution = null, selectedRefresh = null)
    }

    fun selectMode(mode: DisplayModeInfo) {
        _ui.value = _ui.value.copy(
            selectedMode = mode,
            selectedResolution = mode.physicalWidth to mode.physicalHeight,
            selectedRefresh = mode.refreshRate,
        )
    }

    /** 解析出当前 UI 选择对应的具体 Mode。 */
    private fun resolveTargetMode(): DisplayModeInfo? {
        val state = _ui.value
        val external = systemService.primaryExternal() ?: return null

        state.selectedMode?.let { return it }

        val res = state.selectedResolution ?: return DisplayModeSelector.selectBest(external)
        val candidates = external.modesFor(res.first, res.second)
        if (candidates.isEmpty()) return null
        val refresh = state.selectedRefresh ?: 60f
        return DisplayModeSelector.pickRefresh(candidates, refresh)
    }

    /**
     * 应用外屏分辨率，并启动防黑屏倒计时（任务书第 9 节）。
     *
     * @param explicitTarget 指定目标 Mode；为 null 时按当前 UI 选择解析。
     *   （修掉一个竞态：早先 `applyBestResolution()` 先改状态再立刻读状态，
     *    可能读到旧值，导致"最佳分辨率"实际没被应用。）
     */
    fun applyResolution(explicitTarget: DisplayModeInfo? = null) {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            val target = explicitTarget ?: resolveTargetMode()
            if (target == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 无法解析目标 Mode（该分辨率可能不被支持）")
                return@launch
            }

            // 目标与当前完全相同就明确告知，不再做一次无意义的"切换"（避免被误读为失败）
            val current = external.currentMode
            if (DisplayModeSelector.isSameMode(target, current)) {
                appendLog("外接屏当前已经是 ${target.label}，无需切换")
                _ui.value = _ui.value.copy(
                    lastResult = "✅ 外接屏已经是 ${target.label}，无需切换。\n" +
                        "提示：若要验证能否切换，请在上方列表中选择**另一个**分辨率。",
                )
                return@launch
            }

            val allowFallback = settings.useForcedSizeFallback.first()

            // 快照：切换前的「真实 Mode」与「逻辑尺寸」，两者都要，才能完整回滚
            val beforeMode = current
            val beforeSize = systemService.captureExternalSize(external.displayId)
            appendLog(
                "准备切换外屏到 ${target.label}（切换前 mode=${beforeMode?.label ?: "未知"}, " +
                    "size=${beforeSize?.let { "${it.first}x${it.second}" } ?: "未知"}）",
            )

            _ui.value = _ui.value.copy(busy = true)
            val result = systemService.applyExternalMode(external.displayId, target, allowFallback)
            _ui.value = _ui.value.copy(busy = false, lastResult = result.toText())
            appendLog(result.toText().replace("\n", " / "))

            // 统一的回滚动作：先把真实 Mode 切回去，再恢复逻辑尺寸。
            // 只恢复尺寸是不够的 —— 如果真实 Mode 切到了显示器不支持的时序，
            // 外屏会一直黑屏，必须把 Mode 也切回去。
            suspend fun rollback(tag: String) {
                appendLog("$tag：回滚到切换前配置")
                val lines = mutableListOf<String>()
                if (beforeMode != null) {
                    val rm = systemService.applyExternalMode(external.displayId, beforeMode, allowFallback)
                    lines += "恢复 Mode：${rm.toText()}"
                }
                if (beforeSize != null) {
                    val rs = systemService.restoreExternalSize(external.displayId, beforeSize.first, beforeSize.second)
                    lines += "恢复尺寸：${rs.toText()}"
                } else {
                    val rc = systemService.clearExternalForcedSize(external.displayId)
                    lines += "清除尺寸覆盖：${rc.toText()}"
                }
                _ui.value = _ui.value.copy(lastResult = lines.joinToString("\n"))
            }

            if (!result.ok) {
                rollback("切换失败，立即回滚")
                return@launch
            }

            // 防黑屏确认窗口（任务书第 9 节）：15 秒内不确认就回滚
            startConfirmCountdown(
                title = "是否保留此设置？",
                detail = "当前：${target.label}",
                onKeep = {
                    appendLog("用户确认保留 ${target.label}")
                    settings.setPreferredExternalSize("${target.physicalWidth}x${target.physicalHeight}")
                    settings.setPreferredRefreshRate(target.refreshRate.toInt())
                    settings.setPreferredModeId(target.modeId)
                },
                onRollback = { rollback("⏱ 超时未确认") },
            )
        }
    }

    /** 一键使用最佳分辨率（把目标显式传下去，避免状态竞态）。 */
    fun applyBestResolution() {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            val best = DisplayModeSelector.selectBest(external)
            if (best == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 外接屏未上报 supportedModes")
                return@launch
            }
            selectMode(best)
            applyResolution(explicitTarget = best)
        }
    }

    /** 通用防黑屏倒计时。 */
    private fun startConfirmCountdown(
        title: String,
        detail: String,
        onKeep: suspend () -> Unit,
        onRollback: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            var left = DisplayHotplugManager.CONFIRM_TIMEOUT_SECONDS
            _ui.value = _ui.value.copy(
                pendingConfirm = PendingConfirm(title, detail, left, onKeep, onRollback),
            )
            while (left > 0) {
                delay(1000)
                left--
                val cur = _ui.value.pendingConfirm
                if (cur == null) return@launch // 用户已处理
                _ui.value = _ui.value.copy(pendingConfirm = cur.copy(secondsLeft = left))
            }
            // 超时 → 回滚
            val pending = _ui.value.pendingConfirm
            _ui.value = _ui.value.copy(pendingConfirm = null)
            pending?.onRollback?.invoke()
        }
    }

    fun confirmPending() {
        val pending = _ui.value.pendingConfirm ?: return
        _ui.value = _ui.value.copy(pendingConfirm = null)
        viewModelScope.launch { pending.onKeep() }
    }

    fun cancelPending() {
        val pending = _ui.value.pendingConfirm ?: return
        _ui.value = _ui.value.copy(pendingConfirm = null)
        viewModelScope.launch { pending.onRollback() }
    }

    fun clearForcedSize() {
        viewModelScope.launch {
            val external = systemService.primaryExternal() ?: return@launch
            val r = systemService.clearExternalForcedSize(external.displayId)
            _ui.value = _ui.value.copy(lastResult = r.toText())
        }
    }

    // ------------------------------------------------------------------
    // 角色手动指定
    // ------------------------------------------------------------------

    fun setRole(displayId: Int, role: DisplayRole) {
        viewModelScope.launch {
            settings.setRoleOverride(displayId, role)
            appendLog("用户手动指定 displayId=$displayId 为 $role")
            delay(200)
            refreshDisplays()
        }
    }

    // ------------------------------------------------------------------
    // 自动开关
    // ------------------------------------------------------------------

    fun setAutoNative(value: Boolean) = viewModelScope.launch { settings.setAutoNativeResolution(value) }
    fun setAutoInternalOff(value: Boolean) = viewModelScope.launch { settings.setAutoInternalScreenOff(value) }
    fun setAutoRestore(value: Boolean) = viewModelScope.launch { settings.setAutoRestoreInternalScreen(value) }
    fun setLaunchMoonlight(value: Boolean) = viewModelScope.launch { settings.setLaunchMoonlight(value) }
    fun setForcedSizeFallback(value: Boolean) = viewModelScope.launch { settings.setUseForcedSizeFallback(value) }

    val autoNative = settings.autoNativeResolution
    val autoInternalOff = settings.autoInternalScreenOff
    val autoRestore = settings.autoRestoreInternalScreen
    val launchMoonlight = settings.launchMoonlight
    val forcedSizeFallback = settings.useForcedSizeFallback

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    fun buildDiagnostics() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val extra = mutableListOf<String>()
            runCatching {
                extra += "--- UserService 自检 ---"
                extra += systemService.collectSystemInfo()
                extra += "--- 物理屏 ---"
                extra += systemService.listPhysicalDisplays()
            }
            val text = systemService.buildDiagnosticsText(
                displays = _ui.value.displays,
                shizukuState = """
                    状态: ${_ui.value.shizuku.stage}
                    说明: ${_ui.value.shizuku.message}
                    Shizuku 版本: ${_ui.value.shizuku.shizukuVersion}
                    UserService uid: ${_ui.value.shizuku.serviceUid}
                    AIDL: ${ShizukuManager.aidlDescriptor()}
                """.trimIndent(),
                extra = extra,
            )
            _ui.value = _ui.value.copy(busy = false, diagnostics = text, showDiagnostics = true)
        }
    }

    fun copyDiagnostics(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("PadDisplay 诊断信息", _ui.value.diagnostics))
        appendLog("诊断信息已复制到剪贴板")
    }

    fun closeDiagnostics() {
        _ui.value = _ui.value.copy(showDiagnostics = false)
    }
}
