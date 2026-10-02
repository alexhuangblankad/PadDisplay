package com.paddisplay.app.system

import android.os.Build
import android.os.IBinder

/**
 * 外接屏显示模式控制：**扩展 / 复制 / 仅外接屏**（对标 Windows 的 Win+P）。
 *
 * ## 为什么不能用「镜像 API」
 *
 * AOSP 早期版本有 `IWindowManager.setDisplayIdToMirror(int displayId)`，
 * 但**它在 Android 14 就被移除了** —— 本项目从 AOSP 各 tag 解析 `IWindowManager.aidl`
 * 后确认 34/35/36 三个版本里都不存在这个方法。
 *
 * ## 那怎么切换「扩展 / 复制」
 *
 * AOSP 的 `DisplayContent.shouldBeMirrored()` 规则很明确：
 *
 * ```
 * shouldBeMirrored()
 *   = !mDisplayWindowSettings.shouldBeEnabled(...)
 *     || (shouldForceDesktopMode() && windowingMode == WINDOWING_MODE_FULLSCREEN)
 * ```
 *
 * 而 `DisplayWindowSettings.shouldForceDesktopMode()` 是：
 *
 * ```
 * return mDisplayId != DEFAULT_DISPLAY && mWindowManagerService.mContext.getResources()
 *         .getBoolean(com.android.internal.R.bool.config_isDesktopModeSupported)
 *     && Settings.Global.getInt(cr, DEVELOPMENT_FORCE_DESKTOP_MODE, 0) == 1
 * ```
 *
 * 也就是说：**只要不是桌面模式设备（`config_isDesktopModeSupported == false`），
 * 外接屏就不会被强制镜像 —— 而是各自独立显示（= 扩展）。**
 *
 * 所以本项目的做法是：
 * - **扩展**：把外接屏的 windowingMode 设为 `FULLSCREEN(1)`，并确保它没有被 disable。
 * - **复制**：向用户说明这是系统/厂商行为（多数手机平板默认就是复制），
 *   本应用不提供强制镜像（AOSP 已移除该 API，硬做会动到 display 的 enable 状态，
 *   风险太高且容易变砖式黑屏）。
 * - 所有模式切换后都**读回 `getWindowingMode` 验证**，并把真实状态显示出来，
 *   绝不像上一版那样「报成功其实什么都没做」。
 *
 * ## 重要安全约束
 *
 * 只写 **displayId 对应那一个 display** 的 windowingMode。
 * 绝不调用不带 displayId 的全局设置，也绝不改动内屏的 windowingMode。
 */
class DisplayMirrorController(
    private val windowManagerBinder: IBinder?,
) {

    companion object {
        /** `WindowConfiguration.WINDOWING_MODE_*`（AOSP 固定值）。 */
        const val WINDOWING_MODE_UNDEFINED = 0
        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_PINNED = 2
        const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3
        const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4
        const val WINDOWING_MODE_FREEFORM = 5
        const val WINDOWING_MODE_MULTI_WINDOW = 6

        fun windowingModeName(mode: Int): String = when (mode) {
            WINDOWING_MODE_UNDEFINED -> "UNDEFINED(0)"
            WINDOWING_MODE_FULLSCREEN -> "FULLSCREEN(1)"
            WINDOWING_MODE_PINNED -> "PINNED(2)"
            WINDOWING_MODE_SPLIT_SCREEN_PRIMARY -> "SPLIT_PRIMARY(3)"
            WINDOWING_MODE_SPLIT_SCREEN_SECONDARY -> "SPLIT_SECONDARY(4)"
            WINDOWING_MODE_FREEFORM -> "FREEFORM(5)"
            WINDOWING_MODE_MULTI_WINDOW -> "MULTI_WINDOW(6)"
            else -> "MODE($mode)"
        }

        /**
         * IWindowManager.aidl 解析出的确定 code。
         *
         * ⚠️ 这些值是用**真实 `aidl.exe` 编译器**验证过的：
         * 把 AOSP `IWindowManager.api34/35/36.aidl` 的方法名按声明顺序抽出来，
         * 生成同序骨架 AIDL 编译，再读它写出的 `TRANSACTION_*` 常量。
         *
         * | 方法 | API 34 | API 35 | API 36 |
         * |---|---|---|---|
         * | getWindowingMode | 97 | 98 | 98 |
         * | setWindowingMode | 98 | 99 | 99 |
         *
         * （早期版本写成 95/96 与 96/97，会打到相邻方法；
         *   现在每次写入后都读回验证，不会谎报成功。）
         */
        private fun getWindowingModeCode(): Int =
            if (Build.VERSION.SDK_INT >= 35) 98 else 97

        private fun setWindowingModeCode(): Int =
            if (Build.VERSION.SDK_INT >= 35) 99 else 98
    }

    data class Report(val ok: Boolean, val channel: String, val detail: String) {
        fun toText(): String = if (ok) "✅ $channel: $detail" else "❌ $channel: $detail"
    }

    /** 读取某个 display 当前的 windowingMode（读回验证用）。 */
    fun getWindowingMode(displayId: Int): Int? = AidlCodec.call(
        binder = windowManagerBinder,
        descriptor = AidlCodec.DESCRIPTOR_WINDOW_MANAGER,
        label = "getWindowingMode($displayId)",
        code = getWindowingModeCode(),
        writeArgs = { it.writeInt(displayId) },
        readReply = { it.readInt() },
    ).getOrNull()

    /** 设置某个 display 的 windowingMode。 */
    fun setWindowingMode(displayId: Int, mode: Int): Report {
        val channel = "IWindowManager.setWindowingMode"
        if (windowManagerBinder == null) {
            return Report(false, channel, "取不到 window 系统服务（需要 Shizuku）")
        }
        val before = getWindowingMode(displayId)

        val result = AidlCodec.callVoid(
            binder = windowManagerBinder,
            descriptor = AidlCodec.DESCRIPTOR_WINDOW_MANAGER,
            label = "setWindowingMode($displayId, ${windowingModeName(mode)})",
            code = setWindowingModeCode(),
            writeArgs = { data ->
                data.writeInt(displayId)
                data.writeInt(mode)
            },
        )

        return result.fold(
            onSuccess = {
                val after = getWindowingMode(displayId)
                if (after == mode) {
                    Report(
                        true,
                        channel,
                        "displayId=$displayId ${windowingModeName(before ?: -1)} -> ${windowingModeName(after)}",
                    )
                } else {
                    Report(
                        false,
                        channel,
                        "写入后读回不一致：期望 ${windowingModeName(mode)}，实际 " +
                            "${after?.let { windowingModeName(it) } ?: "(读不到)"}",
                    )
                }
            },
            onFailure = { Report(false, channel, "displayId=$displayId 失败 ${Reflect.describe(it)}") },
        )
    }

    /**
     * 切换到「扩展」模式：把外接屏设为独立的 FULLSCREEN display。
     *
     * 这是 per-display 的设置，只写外接屏，不碰内屏。
     *
     * ⚠️ 关于"成功"的判定（审计 F2）：
     * 早先版本在这里无条件塞了一条 `Report(true, "当前 windowingMode", …)`
     * 作为信息展示，结果上层用 `contains("✅")` 判成功时**永远为真**，
     * 即使真正要做的 set 完全没生效 —— 典型的"静默失败却报成功"。
     *
     * 现在把「信息性输出」与「结果判定」彻底分开：
     * - 信息性输出只放在 [info]，不参与成功判定；
     * - 只有 [setWindowingMode] 返回的那条报告才决定成败。
     */
    fun applyExtendMode(externalDisplayId: Int): ExtendResult {
        val before = getWindowingMode(externalDisplayId)
        val info = "切换前 windowingMode = " +
            (before?.let { windowingModeName(it) } ?: "(读不到，可能没有 Shizuku 权限)")

        // 已经是 FULLSCREEN 就无需再写，避免无意义的重配置触发音频/显示重算
        if (before == WINDOWING_MODE_FULLSCREEN) {
            return ExtendResult(
                ok = true,
                report = Report(true, "扩展模式", "外接屏已经是独立的 FULLSCREEN 屏幕，无需修改"),
                info = info,
            )
        }

        val report = setWindowingMode(externalDisplayId, WINDOWING_MODE_FULLSCREEN)
        return ExtendResult(ok = report.ok, report = report, info = info)
    }

    /** 扩展模式切换结果：把结果与信息性输出分开，避免信息行污染成功判定。 */
    data class ExtendResult(val ok: Boolean, val report: Report, val info: String)

    /**
     * 切换到「镜像 / 复制」模式。
     *
     * AOSP 在 Android 14 移除了强制镜像的 API，本应用**不做**这件事：
     * 强行改 display 的 enable 状态风险太高（可能让内屏也一起黑掉）。
     * 这里如实报告，并给用户可执行的替代做法。
     */
    fun applyMirrorMode(externalDisplayId: Int): List<Report> = listOf(
        Report(
            false,
            "复制模式",
            "Android 14+ 已移除强制镜像的隐藏 API（IWindowManager.setDisplayIdToMirror 在 34/35/36 的 AIDL 里都不存在），" +
                "本应用不提供强制复制，以免误操作导致内屏一并熄灭。\n" +
                "替代做法：多数 ColorOS 设备在接入外接屏时默认就是复制；" +
                "若要切回复制，请断开重连外接屏，或在系统的「投屏 / 显示」设置里切换。",
        ),
    )

    /** 诊断：显示模式相关状态。 */
    fun describe(externalDisplayId: Int?): String = buildString {
        appendLine("window binder: ${AidlCodec.describeBinder(windowManagerBinder)}")
        appendLine("getWindowingMode code: ${getWindowingModeCode()} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("setWindowingMode code: ${setWindowingModeCode()}")
        appendLine("setDisplayIdToMirror: AOSP 34/35/36 均不存在（Android 14 起移除）")
        if (externalDisplayId != null) {
            appendLine(
                "外接屏(displayId=$externalDisplayId) windowingMode: " +
                    (getWindowingMode(externalDisplayId)?.let { windowingModeName(it) } ?: "(读不到)"),
            )
        }
    }
}
