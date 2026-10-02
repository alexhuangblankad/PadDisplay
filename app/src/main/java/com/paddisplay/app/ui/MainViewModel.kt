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
    )

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

    fun refreshDisplays() {
        viewModelScope.launch {
            val overrides = settings.roleOverrides.first()
            val displays = systemService.enumerateDisplays(overrides)
            _ui.value = _ui.value.copy(
                displays = displays,
                externalConnected = displays.any { d -> d.isExternal },
            )
        }
    }

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
     * 流程：快照 → 应用 → 弹倒计时 → 用户不确认就回滚。
     */
    fun applyResolution() {
        viewModelScope.launch {
            val external = systemService.primaryExternal()
            if (external == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 未检测到外接显示器")
                return@launch
            }
            val target = resolveTargetMode()
            if (target == null) {
                _ui.value = _ui.value.copy(lastResult = "❌ 无法解析目标 Mode（该分辨率可能不被支持）")
                return@launch
            }

            val allowFallback = settings.useForcedSizeFallback.first()

            // 快照：切换前的「真实 Mode」与「逻辑尺寸」，两者都要，才能完整回滚。
            val beforeMode = external.currentMode
            val beforeSize = systemService.captureExternalSize(external.displayId)
            appendLog(
                "准备切换外屏到 ${target.label}（切换前 mode=${beforeMode?.label ?: "未知"}, " +
                    "size=${beforeSize?.let { "${it.first}x${it.second}" } ?: "未知"}）",
            )

            _ui.value = _ui.value.copy(busy = true)
            val result = systemService.applyExternalMode(external.displayId, target, allowFallback)
            _ui.value = _ui.value.copy(busy = false, lastResult = result.toText())
            appendLog(result.toText().replace("\n", " / "))

            // 统一的回滚动作：先恢复真实 Mode，再恢复逻辑尺寸。
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
                // 失败立即回滚，不必等倒计时
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

    /** 一键使用最佳分辨率。 */
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
            applyResolution()
        }
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
