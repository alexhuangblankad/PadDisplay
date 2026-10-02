package com.paddisplay.app.automation

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.paddisplay.app.data.SettingsRepository
import com.paddisplay.app.display.DisplayRepository
import com.paddisplay.app.display.DisplaySnapshot
import com.paddisplay.app.system.Reflect
import com.paddisplay.app.system.SystemDisplayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 外接屏热插拔自动模式 + 防黑屏 failsafe（任务书第 8 / 9 / 12 节）。
 *
 * ## 两个不可妥协的硬性要求
 *
 * **要求 1（第 8 节，failsafe）**：只要检测到「外接屏被移除」，
 * 立刻无条件把内屏点亮，不管自动模式开关是什么状态。
 * 否则用户会在「关了内屏 → 拔线」之后陷入全黑、无法操作。
 *
 * **要求 2（第 9 节，防黑屏回滚）**：切换分辨率/关屏之前先快照，
 * 然后给用户一个倒计时确认窗口；用户不确认就自动回滚。
 *
 * ## 自动模式流程（第 12 节）
 * ```
 * onDisplayAdded(外接屏)
 *   ↓ 延迟 800ms（等待 supportedModes 稳定）
 *   ↓ 重新枚举，确认外屏仍在
 *   ↓ 选最佳 Mode 并应用
 *   ↓ 确认外屏仍存在
 *   ↓ 关内屏（若开关开启）
 * ```
 */
class DisplayHotplugManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val systemService: SystemDisplayService,
) {

    companion object {
        private const val TAG = "PadDisplay/Hotplug"

        /** 等 supportedModes 稳定的延迟（任务书建议 500~1500ms）。 */
        const val HOTPLUG_SETTLE_MS = 900L

        /** 防黑屏确认倒计时秒数。 */
        const val CONFIRM_TIMEOUT_SECONDS = 15
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var started = false

    /** 内屏当前是否被我们关掉了（决定拔线时是否需要恢复）。 */
    @Volatile
    var internalDisplayTurnedOff: Boolean = false
        private set

    /** 是否有外接屏。 */
    private val _externalConnected = MutableStateFlow(false)
    val externalConnected: StateFlow<Boolean> = _externalConnected.asStateFlow()

    /** 事件日志，显示在 UI 上。 */
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _events.value = (_events.value + "[$stamp] $msg").takeLast(80)
    }

    // ------------------------------------------------------------------

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            log("onDisplayAdded(displayId=$displayId)")
            scope.launch { handleDisplayAdded(displayId) }
        }

        override fun onDisplayRemoved(displayId: Int) {
            log("onDisplayRemoved(displayId=$displayId)")
            scope.launch { handleDisplayRemoved(displayId) }
        }

        override fun onDisplayChanged(displayId: Int) {
            scope.launch { refreshExternalFlag() }
        }
    }

    fun start() {
        if (started) return
        started = true
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        log("热插拔监听已启动")
        scope.launch { refreshExternalFlag() }
    }

    fun stop() {
        if (!started) return
        started = false
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        runCatching { dm?.unregisterDisplayListener(displayListener) }
        log("热插拔监听已停止")
    }

    /**
     * 音频保护：把音频输出从「显示器」拉回到用户真正想用的设备。
     *
     * 优先级：
     * 1. 用户上次显式选定的设备（如果现在还连着）
     * 2. 当前已连接的蓝牙 / 有线耳机
     * 3. 平板内置扬声器
     *
     * 找到目标后交给 UserService 固定「媒体」音频策略。
     * 若没有 Shizuku 权限，会退化成只固定通话音，并如实记录失败原因。
     */
    private suspend fun applyAudioProtection() {
        val outputs = systemService.audio.availableOutputs()
        if (outputs.isEmpty()) {
            log("音频保护：读不到输出设备列表，跳过")
            return
        }

        val preferredId = settings.preferredAudioDeviceId.first()
        val chosen = outputs.firstOrNull { it.id == preferredId && !it.isDisplayLike }
            ?: outputs.firstOrNull { !it.isDisplayLike && isHeadset(it.type) }
            ?: outputs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull { !it.isDisplayLike }

        if (chosen == null) {
            log("音频保护：没有找到非显示器的输出设备，跳过")
            return
        }

        val current = systemService.audio.currentMediaOutput()
        if (current != null && current.id == chosen.id) {
            log("音频保护：媒体音频已经在「${chosen.typeName}」，无需处理")
            return
        }

        log("音频保护：把媒体音频从「${current?.let { com.paddisplay.app.system.AudioRoutingController.typeLabel(it.type) } ?: "未知"}」切到「${chosen.typeName}」")
        val r = systemService.setAudioOutputDevice(chosen.id, pinMedia = true, pinComm = true)
        log("音频保护结果：${r.toText().replace("\n", " / ")}")
    }

    private fun isHeadset(type: Int): Boolean = when (type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        -> true
        else -> false
    }

    /** 供 UI 手动触发音频保护。 */
    suspend fun applyAudioProtectionNow(): String {
        applyAudioProtection()
        return "已尝试把音频输出切回平板侧设备，详见事件日志"
    }

    private suspend fun refreshExternalFlag() {
        // 枚举会走反射 + Binder，放到 IO 线程，避免卡主线程
        val displays = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            systemService.enumerateDisplays(currentRoleOverrides())
        }
        val ext = displays.any { it.isExternal }
        if (_externalConnected.value != ext) {
            _externalConnected.value = ext
        }
    }

    private suspend fun currentRoleOverrides() = settings.roleOverrides.first()

    // ------------------------------------------------------------------
    // 外屏接入
    // ------------------------------------------------------------------

    private suspend fun handleDisplayAdded(displayId: Int) {
        // 1) 等 supportedModes 稳定
        delay(HOTPLUG_SETTLE_MS)

        // 2) 重新枚举，确认这是一块外接屏且仍然存在
        val displays = systemService.enumerateDisplays(currentRoleOverrides())
        val target = displays.firstOrNull { it.displayId == displayId && it.isExternal }
            ?: displays.firstOrNull { it.isExternal }
            ?: run {
                log("displayId=$displayId 加入后未识别为外接屏，忽略")
                return
            }

        _externalConnected.value = true
        log("检测到外接屏：${target.name} ${target.currentModeLabel}，支持 ${target.supportedModes.size} 个模式")

        // 2.5) 音频保护 —— 这一步直接对应用户的真实痛点：
        //      Android 默认把 USB-C / DP 显示器当成音频输出，一线连之后
        //      用户连着的蓝牙耳机就「没声」了。这里在接入外屏后立刻把
        //      音频输出重新钉回用户选择的设备（默认优先耳机 / 内置扬声器）。
        if (settings.preferInternalAudioOnExternal.first()) {
            applyAudioProtection()
        }

        // 3) 应用最佳分辨率（若开启）
        if (settings.autoNativeResolution.first()) {
            val allowFallback = settings.useForcedSizeFallback.first()
            val best = com.paddisplay.app.display.DisplayModeSelector.selectBest(target)
            if (best == null) {
                log("外接屏未上报 supportedModes，跳过自动分辨率")
            } else if (com.paddisplay.app.display.DisplayModeSelector.isSameMode(best, target.currentMode)) {
                log("外接屏已是最佳模式 ${best.label}，无需切换")
            } else {
                log("自动切换到 ${best.label} …")
                // 防黑屏：先决定是否需要倒计时确认
                val result = systemService.applyExternalMode(target.displayId, best, allowFallback)
                log(result.toText().replace("\n", " / "))

                // 4) 确认外屏仍然存在
                val stillThere = systemService.enumerateDisplays(currentRoleOverrides())
                    .any { it.displayId == target.displayId }
                if (!stillThere) {
                    log("⚠️ 切换后外接屏消失，判定为不兼容模式，尝试回滚")
                    // 尝试回到当前上报的模式
                    target.currentMode?.let { old ->
                        systemService.applyExternalMode(target.displayId, old, allowFallback)
                    }
                    return
                }
            }
        }

        // 5) 关闭内屏（若开启）
        if (settings.autoInternalScreenOff.first()) {
            log("自动关闭平板内屏…")
            val result = systemService.turnOffInternalDisplay()
            log(result.toText().replace("\n", " / "))
            if (result.ok) {
                internalDisplayTurnedOff = true
                // 持久化：即使 App 之后被杀，下次启动也知道内屏是关着的
                systemService.markInternalOff(true)
            }
        }
    }

    // ------------------------------------------------------------------
    // 外屏拔出 —— failsafe，必须无条件执行
    // ------------------------------------------------------------------

    private suspend fun handleDisplayRemoved(displayId: Int) {
        val displays = systemService.enumerateDisplays(currentRoleOverrides())
        val anyExternal = displays.any { it.isExternal }

        if (!anyExternal) _externalConnected.value = false

        // 判据非常简单也非常刻意：**现在没有外接屏了，就一定把内屏点亮。**
        //
        // 不依赖「被移除的是不是外屏」（内屏被关掉后可能直接不在枚举里，
        // 导致这里的判定不可靠），也不依赖自动模式开关
        // （failsafe 的优先级高于用户的自动模式偏好）。
        // 宁可多点一次内屏，也不能让用户黑屏。
        if (!anyExternal) {
            log("没有外接屏了（displayId=$displayId 被移除）→ 恢复内屏（failsafe）")

            // 顺手把音频输出固定也清掉：外接屏都拔了，就没有「显示器抢音频」的问题，
            // 此时应交回系统自动路由，避免留下任何跨会话的音频状态。
            runCatching {
                val r = systemService.clearAudioOutputPreference()
                log("音频固定已清除：${r.toText().replace("\n", " / ")}")
            }.onFailure { log("清除音频固定失败：${Reflect.describe(it)}") }

            // 等 DisplayManager 状态稳定，失败则重试
            delay(300)
            val result = systemService.restoreInternalDisplayWithRetry(attempts = 4)
            log(result.toText().replace("\n", " / "))
            if (result.ok) {
                internalDisplayTurnedOff = false
                systemService.markInternalOff(false)
            } else {
                log("⚠️ 内屏恢复失败，请手动点「重新打开平板屏幕」")
            }
        } else {
            log("displayId=$displayId 移除，仍有外接屏，无需恢复")
            refreshExternalFlag()
        }
    }

    /**
     * 手动关内屏（UI 按钮）之后调用，让热插拔逻辑知道「内屏是被我们关的」，
     * 这样拔线时一定会恢复。同时把状态持久化，供 App 重启后的自检使用。
     */
    fun markInternalTurnedOff(turnedOff: Boolean) {
        internalDisplayTurnedOff = turnedOff
        // 持久化是 IO 操作，丢到自己的 scope 里，不阻塞调用方
        scope.launch { systemService.markInternalOff(turnedOff) }
    }

    /**
     * 安全兜底：无论当前状态如何，强制点亮内屏。
     * 用于 App 启动、UI 上的「重新打开平板屏幕」按钮、以及崩溃恢复。
     */
    suspend fun forceRestoreInternalDisplay(): SystemDisplayService.OpResult {
        val result = systemService.turnOnInternalDisplay()
        log("强制恢复内屏: ${result.toText().replace("\n", " / ")}")
        if (result.ok) internalDisplayTurnedOff = false
        return result
    }

    /** 给 UI 用的外屏快照。 */
    suspend fun currentExternal(): DisplaySnapshot? =
        systemService.enumerateDisplays(currentRoleOverrides()).firstOrNull { it.isExternal }
}
