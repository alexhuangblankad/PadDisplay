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
    private val densityOriginal = app.getSharedPreferences("display_density_original", Context.MODE_PRIVATE)

    private fun densityKey(display: DisplaySnapshot) =
        "${display.name}|${display.address}|${display.physicalDisplayId}"

    private fun sameExternal(display: DisplaySnapshot): Boolean =
        systemService.enumerateDisplays().any {
            it.displayId == display.displayId && it.typeCode == 2 && densityKey(it) == densityKey(display)
        }

    fun adjustExternalScale(percent: Int?) {
        if (_ui.value.busy || _ui.value.pendingConfirm != null) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            var before: com.paddisplay.app.system.DisplayDensityController.State? = null
            var target: DisplaySnapshot? = null
            try {
                val external = systemService.primaryExternal() ?: error("未连接外屏")
                require(external.typeCode == 2 && external.displayId > 0) { "只支持物理外屏缩放" }
                target = external
                before = systemService.displayDensity.read(external.displayId)
                val original = before
                val key = densityKey(external)
                if (!densityOriginal.contains(key)) {
                    check(densityOriginal.edit().putInt(key, original.override ?: -1).commit()) {
                        "无法保存缩放恢复记录"
                    }
                }
                val dpi = percent?.let {
                    require(it in 75..200)
                    (original.physical * it / 100.0).toInt()
                }
                check(sameExternal(external)) { "外屏已断开或重新枚举，请刷新后再试" }
                val actual = systemService.displayDensity.write(external.displayId, dpi)
                _ui.value = _ui.value.copy(lastResult = "外屏缩放已读回：${actual.effective} DPI，请确认界面是否合适。")
                startConfirmCountdown(
                    "保留外屏缩放？", "当前 ${actual.effective} DPI；未确认将恢复修改前的大小。",
                    onKeep = {
                        if (percent != null) com.paddisplay.app.desktop.DesktopStore(getApplication()).saveScale(external, percent)
                        refreshDisplays()
                    },
                    onRollback = {
                        runCatching {
                            check(sameExternal(external)) { "原外屏已断开，未向其它屏写入" }
                            systemService.displayDensity.write(external.displayId, original.override)
                        }.fold(
                            { _ui.value = _ui.value.copy(lastResult = "已读回确认恢复原外屏缩放") },
                            { _ui.value = _ui.value.copy(lastResult = "缩放恢复失败：${it.message}；请用恢复原缩放重试") },
                        )
                        refreshDisplays()
                    },
                )
            } catch (t: Throwable) {
                val external = target
                val original = before
                val rollback = if (external != null && original != null) runCatching {
                    check(sameExternal(external)) { "原外屏已断开" }
                    systemService.displayDensity.write(external.displayId, original.override)
                }.fold({ "已恢复修改前 DPI" }, { "恢复失败：${it.message}" }) else "尚未修改"
                _ui.value = _ui.value.copy(lastResult = "缩放失败：${t.message}；$rollback")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    private suspend fun restoreOriginalScale(): String {
        val external = systemService.primaryExternal() ?: return "外屏未连接，缩放恢复记录保留"
        val key = densityKey(external)
        if (!densityOriginal.contains(key)) return "本应用未保存该外屏的缩放改动"
        check(sameExternal(external)) { "外屏已重新枚举" }
        val original = densityOriginal.getInt(key, -1).takeIf { it >= 0 }
        systemService.displayDensity.write(external.displayId, original)
        check(densityOriginal.edit().remove(key).commit()) { "DPI 已恢复，但未能清除恢复记录" }
        return "已读回确认恢复原外屏缩放"
    }

    fun restoreExternalScale() {
        if (_ui.value.busy || _ui.value.pendingConfirm != null) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val result = runCatching {
                val external = systemService.primaryExternal()
                val restored = restoreOriginalScale()
                if (external != null) com.paddisplay.app.desktop.DesktopStore(getApplication()).clearScale(external)
                restored
            }
            _ui.value = _ui.value.copy(busy = false, lastResult = result.getOrElse { "缩放恢复失败：${it.message}" })
            refreshDisplays()
        }
    }

    fun startHostMode() {
        if (com.paddisplay.app.desktop.DesktopState.state.value.running) {
            com.paddisplay.app.desktop.DesktopService.send(getApplication(), "home")
            return
        }
        if (_ui.value.busy || _ui.value.pendingConfirm != null) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            try {
                val extended = systemService.oneClickExtend(configureWindowPolicy = false)
                check(extended.ok) { extended.toText() }
                val external = systemService.primaryExternal() ?: error("外屏已断开")
                val apps = systemService.listLaunchableApps()
                com.paddisplay.app.desktop.DesktopService.send(getApplication())
                _ui.value = _ui.value.copy(
                    appDrawerExpanded = true, launchableApps = apps,
                    lastResult = extended.toText() + "\n桌面服务已请求启动，鼠标关联结果见桌面状态。",
                )
                appendLog("主机模式服务已请求启动；外屏光标仍需实际确认")
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(lastResult = "主机模式未完成：${t.message}")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
                refreshDisplays()
            }
        }
    }

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
        /** 高级选项面板是否展开（默认收起，避免一屏塞满按钮让人不知道该点哪个） */
        val advancedExpanded: Boolean = false,
        // ---------------- 应用启动器（外接屏的「开始菜单」）----------------
        val launchableApps: List<SystemDisplayService.LaunchableApp> = emptyList(),
        val appDrawerExpanded: Boolean = false,
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

    // ------------------------------------------------------------------
    // 光标错位排查（坐标空间）
    // ------------------------------------------------------------------

    /**
     * 对照各屏的「注入坐标空间」。
     *
     * 参考项目 AdaptiveScreenPlus 实测：Display.getRealSize()/getMetrics()
     * 会被本应用的**兼容缩放**污染（内屏 1920x1080 被报成 1496x1242，
     * 导致右侧 424px 够不到、光标与点击错位）。
     * 唯一可靠的注入空间是 `dumpsys window displays` 的 `cur=WxH`。
     */
    fun probeCoordinateSpaces() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val ids = _ui.value.displays.map { it.displayId }.ifEmpty { listOf(0) }
            val text = systemService.probeCoordinateSpaces(ids)
            _ui.value = _ui.value.copy(
                busy = false,
                diagnostics = "=========== 坐标空间对照 ===========\n\n$text",
                showDiagnostics = true,
            )
            appendLog("已生成坐标空间对照（用于排查光标错位）")
        }
    }

    /** 在指定屏注入一次点击，验证坐标空间是否正确。 */
    fun testInjectTap(target: DesktopTarget) {
        viewModelScope.launch {
            val displayId = when (target) {
                DesktopTarget.INTERNAL -> systemService.internalDisplay()?.displayId
                DesktopTarget.EXTERNAL -> systemService.primaryExternal()?.displayId
            }
            if (displayId == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 找不到目标显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            // 点到屏幕中心
            val d = systemService.byId(displayId)
            val cx = (d?.logicalWidth ?: 100) / 2
            val cy = (d?.logicalHeight ?: 100) / 2
            val r = systemService.injectTapOnDisplay(displayId, cx, cy)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("注入点击测试（${target.label} 中心 $cx,$cy）：${r.toText().replace("\n", " / ")}")
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

    // ------------------------------------------------------------------
    // 多屏拓扑（左右关系）
    // ------------------------------------------------------------------

    /** 探测本机是否支持 DisplayTopology（决定能否设置左右关系）。 */
    fun probeTopology() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val text = systemService.probeDisplayTopology()
            _ui.value = _ui.value.copy(
                busy = false,
                diagnostics = "=========== 多屏拓扑能力探测 ===========\n\n$text",
                showDiagnostics = true,
            )
            appendLog("已探测多屏拓扑能力")
        }
    }

    /**
     * 设置两块屏的左右关系。
     *
     * @param externalOnRight true = 外接屏在右侧
     */
    fun setScreenLayout(position: Int) {
        viewModelScope.launch {
            val internal = systemService.internalDisplay()
            val external = systemService.primaryExternal()
            if (internal == null || external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 需要内屏和外屏都在，才能设置左右关系")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            // 以**内屏为原点**，把外屏放到左/右
            val r = systemService.setDisplayTopologyLayout(
                primaryDisplayId = internal.displayId,
                otherDisplayId = external.displayId,
                position = position,
                primarySize = internal.logicalWidth to internal.logicalHeight,
                otherSize = external.logicalWidth to external.logicalHeight,
            )
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("设置屏幕布局（${positionLabel(position)}）：${r.toText().replace("\n", " / ")}")
        }
    }

    // ------------------------------------------------------------------
    // 两个桌面
    // ------------------------------------------------------------------

    /**
     * 把一个应用启动到指定桌面。
     *
     * 这就是"两个桌面"的实现方式：内屏和外屏各自独立运行应用。
     */
    fun launchOnDesktop(target: DesktopTarget) {
        viewModelScope.launch {
            val displayId = when (target) {
                DesktopTarget.INTERNAL -> systemService.internalDisplay()?.displayId
                DesktopTarget.EXTERNAL -> systemService.primaryExternal()?.displayId
            }
            if (displayId == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 找不到目标显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            // 用系统「设置」作为最稳妥的可启动目标
            val r = systemService.launchAppOnDisplay(displayId, component = "com.android.settings/.Settings")
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("在${target.label}启动应用：${r.toText().replace("\n", " / ")}")
        }
    }

    /** 桌面目标。 */
    enum class DesktopTarget(val label: String) {
        INTERNAL("内屏桌面"),
        EXTERNAL("外屏桌面"),
    }

    /**
     * 一键建立"两个桌面"：先确保扩展模式，再把输入绑到外屏。
     *
     * 这是把前面几步串起来的一键操作。
     */
    fun setupDualDesktop() {
        viewModelScope.launch {
            val internal = systemService.internalDisplay()
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val lines = mutableListOf<String>()

            // 1) 确保外接屏是独立屏幕（扩展）
            val ext = systemService.applyExtendMode(external.displayId)
            lines += (if (ext.ok) "✅ " else "❌ ") + ext.title

            // 2) 内屏保持点亮
            if (_ui.value.internalTurnedOff) {
                val on = systemService.restoreInternalDisplayWithRetry(attempts = 2)
                hotplug.markInternalTurnedOff(!on.ok)
                lines += (if (on.ok) "✅ " else "❌ ") + "内屏已点亮"
            }

            // 3) 把输入绑到外屏（这样才有第二个可操作的桌面）
            val bind = systemService.bindInputToDisplay(external.displayId)
            lines += (if (bind.ok) "✅ " else "❌ ") + bind.title

            // 4) 尝试设置左右关系（不支持也不影响后续）
            if (internal != null) {
                val topo = systemService.setDisplayTopologyLayout(
                    primaryDisplayId = internal.displayId,
                    otherDisplayId = external.displayId,
                    position = 2, // 外屏在右
                    primarySize = internal.logicalWidth to internal.logicalHeight,
                    otherSize = external.logicalWidth to external.logicalHeight,
                )
                lines += (if (topo.ok) "✅ " else "⚠️ ") + topo.title
            }

            _ui.value = _ui.value.copy(busy = false, lastResult = lines.joinToString("\n"))
            appendLog("一键建立双桌面：" + lines.joinToString(" / "))
            delay(500)
            refreshDisplays()
        }
    }

    // ------------------------------------------------------------------
    // 应用启动器（外接屏的「开始菜单」）
    // ------------------------------------------------------------------

    /** 展开/收起应用列表，并在展开时拉取应用清单。 */
    fun toggleAppDrawer() {
        val expand = !_ui.value.appDrawerExpanded
        _ui.value = _ui.value.copy(appDrawerExpanded = expand)
        if (expand && _ui.value.launchableApps.isEmpty()) {
            refreshLaunchableApps()
        }
    }

    /** 拉取可启动应用清单。 */
    fun refreshLaunchableApps() {
        viewModelScope.launch {
            val apps = systemService.listLaunchableApps()
            _ui.value = _ui.value.copy(launchableApps = apps)
            appendLog("可启动应用：${apps.size} 个")
        }
    }

    /** 把某个应用启动到外接屏。 */
    fun launchAppOnExternal(app: SystemDisplayService.LaunchableApp) {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.launchPackageOnDisplay(external.displayId, app.packageName)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("在外屏启动「${app.label}」：${r.toText().replace("\n", " / ")}")
        }
    }

    /** 把某个应用启动到内屏。 */
    fun launchAppOnInternal(app: SystemDisplayService.LaunchableApp) {
        viewModelScope.launch {
            val internal = systemService.internalDisplay()
            if (internal == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 找不到内屏")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.launchPackageOnDisplay(internal.displayId, app.packageName)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("在内屏启动「${app.label}」：${r.toText().replace("\n", " / ")}")
        }
    }
    // ------------------------------------------------------------------
    // MouseFlow 第一轮实验
    // ------------------------------------------------------------------

    /** 探测原生鼠标光标所在屏（MouseFlow 实验第一步）。 */
    fun probePointerDisplay() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val text = systemService.probePointerDisplay()
            _ui.value = _ui.value.copy(
                busy = false,
                diagnostics = "=========== MouseFlow 探测 ===========\n\n$text",
                showDiagnostics = true,
            )
            appendLog("已探测原生指针所在屏")
        }
    }

    /** 强制把指针切到某块屏（第一轮实验的关键按钮）。 */
    fun forcePointer(target: DesktopTarget) {
        viewModelScope.launch {
            val d = when (target) {
                DesktopTarget.INTERNAL -> systemService.internalDisplay()
                DesktopTarget.EXTERNAL -> systemService.primaryExternal()
            }
            if (d == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 找不到目标显示器")
                return@launch
            }
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.forcePointerToDisplay(d.displayId)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("强制指针到 ${target.label}：${r.toText().replace("\n", " / ")}")
        }
    }
    // ------------------------------------------------------------------
    // 桌面模式 / 自由窗口（DeX / TNT 类桌面）
    // ------------------------------------------------------------------

    /** 探测桌面模式能力与当前设置。 */
    fun probeDesktopMode() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val text = systemService.probeDesktopMode()
            _ui.value = _ui.value.copy(
                busy = false,
                diagnostics = "=========== 桌面模式探测 ===========\n\n$text",
                showDiagnostics = true,
            )
            appendLog("已探测桌面模式")
        }
    }

    /** 开启桌面模式（等于在开发者选项里打勾那两个开关）。 */
    fun enableDesktopMode() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.setDesktopMode(enable = true)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("开启桌面模式：${r.toText().replace("\n", " / ")}")
        }
    }

    /** 关闭桌面模式。 */
    fun disableDesktopMode() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.setDesktopMode(enable = false)
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("关闭桌面模式：${r.toText().replace("\n", " / ")}")
        }
    }
    /**
     * 一键完成「我想要的」：外接屏独立显示 + 内屏保持 + 音频留平板 + 外屏开应用。
     */
    fun oneClickExtend() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = systemService.oneClickExtend()
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText())
            appendLog("一键完成：${r.toText().replace("\n", " / ")}")
            delay(600)
            refreshDisplays()
        }
    }

    /**
     * 一键还原所有设置。
     *
     * 这是"救命按钮"：把本应用可能改动过的系统级状态全部恢复。
     * 先点亮内屏，保证用户看得见结果。
     */
    fun restoreAll() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            if (com.paddisplay.app.desktop.DesktopState.state.value.running) {
                com.paddisplay.app.desktop.DesktopService.send(getApplication(), "stop")
                val stopped = kotlinx.coroutines.withTimeoutOrNull(15000) {
                    com.paddisplay.app.desktop.DesktopState.state.first { !it.running }
                }
                if (stopped == null) {
                    _ui.value = _ui.value.copy(busy = false, lastResult = "桌面退出尚未完成，请稍后重试还原。")
                    return@launch
                }
            }
            val r = systemService.restoreAll()
            val mouse = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ShizukuManager.service?.returnMouseToInternal() ?: "Shizuku 未连接，鼠标回内屏未确认" }
                    .getOrElse { "鼠标回内屏失败：${it.message}" }
            }
            val scale = runCatching { restoreOriginalScale() }.getOrElse { "缩放恢复失败：${it.message}" }
            _ui.value = _ui.value.copy(busy = false, lastResult = r.toText() + "\n" + scale + "\n" + mouse)
            appendLog("一键还原：${r.toText().replace("\n", " / ")}")
            hotplug.markInternalTurnedOff(false)
            delay(600)
            refreshDisplays()
        }
    }

    fun returnMouseToTablet() {
        if (_ui.value.busy || com.paddisplay.app.desktop.DesktopState.state.value.running) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val output = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ShizukuManager.service?.returnMouseToInternal() ?: "Shizuku 未连接，无法恢复鼠标" }
                    .getOrElse { "鼠标恢复失败：${it.message}" }
            }
            _ui.value = _ui.value.copy(busy = false, lastResult = output)
            com.paddisplay.app.desktop.DesktopState.state.value = com.paddisplay.app.desktop.DesktopSnapshot(message = output)
            appendLog(output)
        }
    }

    /** 方向标签。 */
    private fun positionLabel(position: Int): String = when (position) {
        0 -> "外屏在左"
        1 -> "外屏在上"
        2 -> "外屏在右"
        3 -> "外屏在下"
        else -> "位置($position)"
    }
    /** 高级选项面板是否展开。 */
    fun toggleAdvanced() {
        _ui.value = _ui.value.copy(advancedExpanded = !_ui.value.advancedExpanded)
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
                com.paddisplay.app.desktop.DesktopState.state.value.running -> DisplayMode.EXTEND
                // Window mode and matching resolutions cannot prove the physical mirror source.
                else -> DisplayMode.UNKNOWN
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
