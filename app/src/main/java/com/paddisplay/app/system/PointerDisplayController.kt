package com.paddisplay.app.system

import android.os.IBinder
import android.view.InputDevice

/**
 * 原生鼠标（Pointer）所在 Display 的探测与切换。
 *
 * # 这套机制的真相（AOSP 16 源码查证）
 *
 * `NativeInputManagerService.setPointerDisplayId(int)` 确实存在，注释是
 * "Set the displayId on which the mouse cursor should be shown."
 * **但它是一个 native 本地接口，不是 Binder 服务** —— 外部进程（哪怕 shell）
 * 无法直接调用它。整个 AOSP 里它只有**一个**调用点：
 *
 * ```java
 * // InputManagerService.setDisplayViewportsInternal()
 * mNative.setPointerDisplayId(mWindowManagerCallbacks.getPointerDisplayId());
 * ```
 *
 * 也就是说：**IMS 是主动从 WMS 取值的，App 无从设置。**
 *
 * # 但换屏能力本身是存在的，而且是系统自己做的
 *
 * 取值逻辑在 `WindowManagerService.InputManagerCallback.getPointerDisplayId()`：
 *
 * ```java
 * public int getPointerDisplayId() {
 *     // 桌面模式没开 → 光标永远留在内屏
 *     if (!mService.mForceDesktopModeOnExternalDisplays) {
 *         return DEFAULT_DISPLAY;
 *     }
 *     // 找最上层的 freeform 显示器
 *     for (int i = mService.mRoot.mChildren.size() - 1; i >= 0; --i) {
 *         ...
 *         // 「自由窗口」开发者选项打开时，系统自动把副屏设为 freeform 模式
 *         // 并模拟"桌面模式"；把指针显示在同一块屏上也顺理成章。
 *         if (displayContent.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
 *             return displayContent.getDisplayId();
 *         }
 *     }
 *     return firstExternalDisplayId;
 * }
 * ```
 *
 * 条件正是两个 Global settings：
 * - `DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS`
 * - `DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT`（打开后副屏进入 freeform）
 *
 * **所以不需要 `setPointerDisplayId`——系统自己会调。**
 * 本类因此做两件事：
 * 1. **探测**：按 AOSP 同一套逻辑预测"系统会把光标放在哪块屏"，并报告依据；
 * 2. **验证/移动光标**：`injectInputEvent` 带 `setDisplayId` 可以真正改变
 *    原生指针的位置（这是可调用的部分）。
 */
class PointerDisplayController(
    private val inputManagerBinder: IBinder?,
    private val shellRunner: (String) -> String,
) {

    companion object {
        private const val DEFAULT_DISPLAY = 0

        /** `Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS` */
        const val KEY_FORCE_DESKTOP_MODE = "development_force_desktop_mode_on_external_displays"

        /** `Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT` */
        const val KEY_FREEFORM = "development_enable_freeform_windows_support"

        /** `WindowConfiguration.WINDOWING_MODE_FREEFORM` */
        const val WINDOWING_MODE_FREEFORM = 5
    }

    data class MouseDevice(
        val id: Int,
        val name: String,
        val sources: Int,
        val vendorId: Int,
        val productId: Int,
        val isExternal: Boolean,
        val associatedDisplayId: Int?,
        val hasRelativeAxes: Boolean,
    ) {
        val isMouse: Boolean
            get() = (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (sources and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
    }

    data class Report(val ok: Boolean, val detail: String) {
        fun toText(): String = if (ok) "✅ $detail" else "❌ $detail"
    }

    /** 枚举物理鼠标（含 vendor/product/relative 轴信息，便于 Moonlight 场景判断）。 */
    fun listMice(): List<MouseDevice> {
        val deviceIds: IntArray = InputDevice.getDeviceIds()
        return deviceIds.toList().mapNotNull { deviceId: Int ->
            val d = InputDevice.getDevice(deviceId) ?: return@mapNotNull null
            val sources = runCatching { d.sources }.getOrDefault(0)
            val isMouse = (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (sources and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
            if (!isMouse) return@mapNotNull null
            val ranges = runCatching { d.motionRanges }.getOrNull().orEmpty()
            MouseDevice(
                id = deviceId,
                name = runCatching { d.name }.getOrDefault("(未知)"),
                sources = sources,
                vendorId = runCatching { d.vendorId }.getOrDefault(0),
                productId = runCatching { d.productId }.getOrDefault(0),
                isExternal = runCatching { d.isExternal }.getOrDefault(false),
                associatedDisplayId = runCatching {
                    Reflect.call(d, "getAssociatedDisplayId").getOrNull() as? Int
                }.getOrNull()?.takeIf { it >= 0 },
                hasRelativeAxes = ranges.any {
                    it.axis == android.view.MotionEvent.AXIS_RELATIVE_X ||
                        it.axis == android.view.MotionEvent.AXIS_RELATIVE_Y
                },
            )
        }
    }

    /** 读一个 Global int 设置。 */
    private fun globalInt(key: String, default: Int = 0): Int? {
        val out = shellRunner("/system/bin/settings get global $key").trim()
        return out.toIntOrNull() ?: (if (out.isEmpty()) default else null)
    }

    private fun globalSet(key: String, value: Int): Boolean {
        shellRunner("/system/bin/settings put global $key $value")
        return globalInt(key) == value
    }

    /**
     * 探测：按 AOSP 的同一套逻辑预测光标所在屏，并给出依据。
     *
     * @param windowingModes displayId -> windowingMode（由调用方从 dumpsys 取得）
     */
    fun analyze(
        displayStates: Map<Int, Boolean>,
        windowingModes: Map<Int, Int>,
    ): String = buildString {
        val forceDesktop = globalInt(KEY_FORCE_DESKTOP_MODE, 0)
        val freeform = globalInt(KEY_FREEFORM, 0)

        appendLine("=== 原生指针（鼠标光标）所在屏 分析 ===")
        appendLine("uid=${android.os.Process.myUid()}")
        appendLine()
        appendLine("--- 两个决定性设置（AOSP InputManagerCallback 直接读它们）---")
        appendLine("  $KEY_FORCE_DESKTOP_MODE = $forceDesktop")
        appendLine("  $KEY_FREEFORM                   = $freeform")
        appendLine()

        appendLine("--- 按 AOSP getPointerDisplayId() 的逻辑推演 ---")
        if (forceDesktop != 1) {
            appendLine("  ❌ 桌面模式未开启（$KEY_FORCE_DESKTOP_MODE != 1）")
            appendLine("     → AOSP 第一行就 `return DEFAULT_DISPLAY`")
            appendLine("     → **原生鼠标光标只会停在内屏（Display 0）**，不可能到外屏")
            appendLine()
            appendLine("  这是当前最可能的现状。要改变它，必须打开桌面模式。")
        } else {
            appendLine("  ✅ 桌面模式已开启")
            val freeformDisplay = windowingModes.entries
                .filter { it.value == WINDOWING_MODE_FREEFORM && displayStates[it.key] != false }
                .maxByOrNull { it.key }
            if (freeformDisplay != null) {
                appendLine("  ✅ 命中 freeform 分支：displayId=${freeformDisplay.key} 是 freeform")
                appendLine("     → AOSP 会 `return ${freeformDisplay.key}`")
                appendLine("     → **原生鼠标光标应在这块屏上**")
            } else {
                appendLine("  ⚠️ 没有 freeform 显示器，走最后的兜底分支：")
                appendLine("     → 返回「最上层非默认显示器」= 你的外接屏")
                val external = windowingModes.keys.filter { it != DEFAULT_DISPLAY && displayStates[it] != false }
                appendLine("     候选：${external.ifEmpty { listOf(DEFAULT_DISPLAY) }}")
            }
            if (freeform != 1) {
                appendLine()
                appendLine("  ⚠️ 但 $KEY_FREEFORM = $freeform")
                appendLine("     该键为 0 时，系统不会把副屏设为 freeform，")
                appendLine("     于是只能走兜底分支。建议把它也设为 1。")
            }
        }
        appendLine()
        appendLine("--- 关于 setPointerDisplayId（任务书重点研究项）---")
        appendLine("  NativeInputManagerService.setPointerDisplayId(int) 存在，注释：")
        appendLine("    \"Set the displayId on which the mouse cursor should be shown.\"")
        appendLine("  但它是 **native 本地接口，不是 Binder 服务**，")
        appendLine("  全 AOSP 只有一处调用：")
        appendLine("    IMS.setDisplayViewportsInternal()")
        appendLine("      -> mNative.setPointerDisplayId(mWindowManagerCallbacks.getPointerDisplayId())")
        appendLine("  即：**IMS 主动从 WMS 取值，App/Shizuku 都无从直接设置。**")
        appendLine()
        appendLine("  所以本项目的正确做法不是去调它，而是**满足它取值的前置条件**：")
        appendLine("    打开桌面模式 + 自由窗口 → 系统自己会把光标移到外屏。")
    }

    /**
     * 真正改变原生指针位置 —— 用 `injectInputEvent` 带 `setDisplayId`。
     *
     * 这是可调用的部分：`PointerInjector` 注入的 MotionEvent 带 displayId 后，
     * input 系统会按该屏处理并更新指针位置。
     *
     * 注意：它移动的是**指针位置**，不是"从 WMS 侧切换指针归属屏"。
     * 后者的开关是那两个 Global settings。
     */
    fun moveTo(displayId: Int, x: Int, y: Int): Report {
        if (inputManagerBinder == null) {
            return Report(false, "取不到 IInputManager（需要 Shizuku）")
        }
        val injector = PointerInjector(inputManagerBinder, shellRunner)
        if (!injector.ready()) {
            return Report(false, "injectInputEvent 不可用：" + injector.describe())
        }
        val r = injector.injectMove(displayId, x, y)
        return Report(r.ok, "移动指针到 displayId=$displayId ($x,$y)：${r.detail}")
    }

    /** 读取当前指针位置（读不到就返回 null，不编造）。 */
    fun currentPosition(displayId: Int): Pair<Float, Float>? = runCatching {
        val out = shellRunner("/system/bin/dumpsys input | grep -i -A3 'Pointer'").trim()
        val m = Regex("""x\s*[:=]\s*([\d.]+).*?y\s*[:=]\s*([\d.]+)""", RegexOption.DOT_MATCHES_ALL).find(out)
        if (m != null) {
            m.groupValues[1].toFloatOrNull()?.let { x ->
                m.groupValues[2].toFloatOrNull()?.let { y -> x to y }
            }
        } else {
            null
        }
    }.getOrNull()

    /** 开关桌面模式（两个键一起设，缺一不可）。 */
    fun setDesktopMode(enable: Boolean): List<Report> {
        val v = if (enable) 1 else 0
        return listOf(
            Report(globalSet(KEY_FORCE_DESKTOP_MODE, v), "写入 $KEY_FORCE_DESKTOP_MODE=$v"),
            Report(globalSet(KEY_FREEFORM, v), "写入 $KEY_FREEFORM=$v"),
        )
    }
}
