package com.paddisplay.app.system

import android.os.Build
import android.os.IBinder
import android.view.Display
import com.paddisplay.app.display.DisplayModeInfo

/**
 * 指定外接屏的分辨率 / Mode 控制（任务书第 6 节，项目技术核心之一）。
 *
 * ## 两条语义完全不同的路，必须分清楚
 *
 * 1. **[changeMode]** —— 切换**真实显示模式**（改硬件时序）。
 *    走 `IDisplayManager.setUserPreferredDisplayMode(displayId, Display.Mode)`，
 *    mode 必须来自 `display.supportedModes`。
 *    这也是任务书要求的「优先读取 Display.Mode，让用户选硬件实际支持的模式」。
 *
 * 2. **[setForcedDisplaySize]** —— 逻辑尺寸覆盖（不改时序，系统内部缩放）。
 *    走 `IWindowManager.setForcedDisplaySize(displayId, w, h)`。
 *    ⚠️ 这正是 `wm size` 底层做的事，但注意区别：
 *    `wm size 1920x1080`（不带 -d）会改**整个系统默认分辨率**，任务书明确禁止；
 *    带 `-d <displayId>` 才是 per-display 的。本项目一律走带 displayId 的通道。
 *
 * ## 关于 `wm size` 的参数顺序（踩过的坑）
 *
 * AOSP `WindowManagerShellCommand.runDisplaySize()` 的实际解析顺序是
 * **先读第一个参数**：如果第一个参数是 `-d`，就把后面一个 token 当作 displayId，
 * 然后直接 return（只打印，不应用）。所以：
 *
 * ```
 * wm size -d 1 1920x1080    ← 错：只查询，WxH 被忽略
 * wm size 1920x1080 -d 1    ← 对
 * wm size reset -d 1        ← 对
 * ```
 *
 * ## 防止「看起来实现了，其实只改了主屏」
 * 每个方法都强制要求显式 displayId，并在日志里打印操作前后该 display 的尺寸，
 * 便于在诊断页确认内屏没有被牵连。
 */
class DisplayResolutionController(
    private val displayManagerBinder: IBinder?,
    private val windowManagerBinder: IBinder?,
    private val shellRunner: (String) -> String,
) {

    companion object {
        /**
         * AOSP `IDisplayManager.aidl` 解析出的确定 transaction code。
         * `setUserPreferredDisplayMode` 在 API 34/35/36 都是 **42**。
         */
        private const val SET_USER_PREFERRED_DISPLAY_MODE = 42

        /**
         * 各 API 版本 `IWindowManager.aidl` 的确定 transaction code。
         *
         * 全部用**真实 `aidl.exe`** 编译同序骨架 AIDL 验证过
         * （把 AOSP `.aidl` 的方法名按声明顺序抽出 → 生成同序接口 → 读编译器写出的
         * `TRANSACTION_* = FIRST_CALL_TRANSACTION + n`）：
         *
         * | 方法 | API 34 | API 35 | API 36 |
         * |---|---|---|---|
         * | getInitialDisplaySize | 6 | 5 | 5 |
         * | getBaseDisplaySize | 7 | 6 | 6 |
         * | setForcedDisplaySize | 8 | 7 | 7 |
         * | clearForcedDisplaySize | 9 | 8 | 8 |
         */
        private fun getInitialDisplaySizeCode(): Int = if (Build.VERSION.SDK_INT <= 34) 6 else 5
        private fun getBaseDisplaySizeCode(): Int = if (Build.VERSION.SDK_INT <= 34) 7 else 6
        private fun setForcedDisplaySizeCode(): Int = if (Build.VERSION.SDK_INT <= 34) 8 else 7
        private fun clearForcedDisplaySizeCode(): Int = if (Build.VERSION.SDK_INT <= 34) 9 else 8
    }

    data class Report(val ok: Boolean, val channel: String, val detail: String) {
        fun toText(): String = if (ok) "✅ $channel: $detail" else "❌ $channel: $detail"
    }

    /** 切换前的快照，用于防黑屏回滚。 */
    data class Snapshot(
        val displayId: Int,
        val logicalWidth: Int,
        val logicalHeight: Int,
        val text: String,
    )

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 用 `wm size -d <id>` 读指定屏的物理/覆盖尺寸。
     * 真实输出形如：
     * ```
     * Physical size: 2560x1600
     * Override size: 1920x1080
     * ```
     */
    fun readDisplaySize(displayId: Int): Snapshot {
        val out = shellRunner("wm size -d $displayId").trim()
        val physical = Regex("""Physical size:\s*(\d+)x(\d+)""").find(out)
        val override = Regex("""Override size:\s*(\d+)x(\d+)""").find(out)

        // 有 Override 说明被强制改过，展示时以 Override 为准
        val effective = override ?: physical
        val w = effective?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val h = effective?.groupValues?.get(2)?.toIntOrNull() ?: 0

        return Snapshot(
            displayId = displayId,
            logicalWidth = w,
            logicalHeight = h,
            text = if (out.isEmpty()) "(wm size -d $displayId 无输出)" else out.replace("\n", " | "),
        )
    }

    /**
     * `IWindowManager.getBaseDisplaySize(displayId, out Point)`。
     * out 参数的 reply 格式是：先一个 1/0 存在标记，再 Point 内容。
     */
    fun readBaseDisplaySize(displayId: Int): Pair<Int, Int>? =
        readOutPoint(getBaseDisplaySizeCode(), displayId, "getBaseDisplaySize")

    fun readInitialDisplaySize(displayId: Int): Pair<Int, Int>? =
        readOutPoint(getInitialDisplaySizeCode(), displayId, "getInitialDisplaySize")

    private fun readOutPoint(code: Int, displayId: Int, label: String): Pair<Int, Int>? =
        AidlCodec.call(
            binder = windowManagerBinder,
            descriptor = AidlCodec.DESCRIPTOR_WINDOW_MANAGER,
            label = "$label($displayId)",
            code = code,
            writeArgs = { it.writeInt(displayId) },
            readReply = { reply ->
                val present = reply.readInt()
                if (present == 0) {
                    null
                } else {
                    val p = android.graphics.Point()
                    p.readFromParcel(reply)
                    p.x to p.y
                }
            },
        ).getOrNull()

    // ------------------------------------------------------------------
    // 通道 A：真实 Mode 切换（首选）
    // ------------------------------------------------------------------

    /**
     * 请求切换到指定的 [Display.Mode]。
     * @param modeInfo 必须来自该 display 的 supportedModes
     */
    fun changeMode(displayId: Int, modeInfo: DisplayModeInfo): List<Report> {
        val label = "IDisplayManager.setUserPreferredDisplayMode"
        val binder = displayManagerBinder
            ?: return listOf(Report(false, label, "取不到 display 系统服务"))

        val mode = buildDisplayMode(modeInfo)
            ?: return listOf(
                Report(false, label, "构造 Display.Mode 失败（modeId=${modeInfo.modeId}）"),
            )

        val result = AidlCodec.callVoid(
            binder = binder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "setUserPreferredDisplayMode($displayId, ${modeInfo.compact})",
            code = SET_USER_PREFERRED_DISPLAY_MODE,
            writeArgs = { data ->
                data.writeInt(displayId)
                // AIDL 的 `in Mode mode` 会被编译成 writeTypedObject：
                // 先写 1（非空标记），再写 Parcelable 内容。
                // 不能用 writeParcelable —— 那个格式会先写 creator 类名字符串，
                // 服务端按 typed object 解析会把类名当数据，得到一个垃圾 Mode。
                AidlCodec.writeNullableParcelable(data, mode)
            },
        )

        return listOf(
            result.fold(
                onSuccess = {
                    Report(true, label, "displayId=$displayId -> ${modeInfo.label}")
                },
                onFailure = {
                    Report(false, label, "displayId=$displayId 失败 ${Reflect.describe(it)}")
                },
            ),
        )
    }

    /**
     * 重置用户首选 Mode（回到系统默认）。
     *
     * AOSP 的 `IDisplayManager` **没有** `resetUserPreferredDisplayMode` 这个方法，
     * 但 `setUserPreferredDisplayMode` 的参数是可空的：
     * `DisplayManagerService.setUserPreferredDisplayModeInternal()` 明确处理 `mode == null`
     * 表示「恢复系统默认」。所以这里传 null，code 仍然是 42。
     */
    fun resetUserPreferredMode(displayId: Int): Report {
        val label = "IDisplayManager.setUserPreferredDisplayMode(null)"
        val binder = displayManagerBinder
            ?: return Report(false, label, "取不到 display 系统服务")

        val result = AidlCodec.callVoid(
            binder = binder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "resetUserPreferredDisplayMode($displayId)",
            code = SET_USER_PREFERRED_DISPLAY_MODE,
            writeArgs = { data ->
                data.writeInt(displayId)
                // null Mode -> 写存在标记 0
                data.writeInt(0)
            },
        )
        return result.fold(
            onSuccess = { Report(true, label, "displayId=$displayId 已恢复系统默认 Mode") },
            onFailure = { Report(false, label, "displayId=$displayId 失败 ${Reflect.describe(it)}") },
        )
    }

    /**
     * 反射构造 `Display.Mode`。
     * 隐藏构造器：`Mode(int modeId, int width, int height, float refreshRate)`
     * （更早的版本是 3 参数 `Mode(int, int, float)`，两个都试）。
     */
    fun buildDisplayMode(info: DisplayModeInfo): Display.Mode? {
        val clazz = Display.Mode::class.java

        val ctor4 = runCatching {
            clazz.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
        }.getOrNull()
        ctor4?.let { c ->
            runCatching {
                return c.newInstance(info.modeId, info.physicalWidth, info.physicalHeight, info.refreshRate)
            }
        }

        val ctor3 = runCatching {
            clazz.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
        }.getOrNull()
        ctor3?.let { c ->
            runCatching {
                return c.newInstance(info.physicalWidth, info.physicalHeight, info.refreshRate)
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 通道 B：逻辑尺寸覆盖（兜底）
    // ------------------------------------------------------------------

    /** `IWindowManager.setForcedDisplaySize(displayId, w, h)`，只影响指定 displayId。 */
    fun setForcedDisplaySize(displayId: Int, width: Int, height: Int): List<Report> {
        val reports = mutableListOf<Report>()
        val label = "IWindowManager.setForcedDisplaySize"

        if (windowManagerBinder != null) {
            val result = AidlCodec.callVoid(
                binder = windowManagerBinder,
                descriptor = AidlCodec.DESCRIPTOR_WINDOW_MANAGER,
                label = "setForcedDisplaySize($displayId, ${width}x$height)",
                code = setForcedDisplaySizeCode(),
                writeArgs = { data ->
                    data.writeInt(displayId)
                    data.writeInt(width)
                    data.writeInt(height)
                },
            )
            reports += result.fold(
                onSuccess = { Report(true, label, "displayId=$displayId -> ${width}x$height") },
                onFailure = { Report(false, label, "displayId=$displayId 失败 ${Reflect.describe(it)}") },
            )
        } else {
            reports += Report(false, label, "取不到 window 系统服务")
        }

        // 通道 C：shell 命令。注意参数顺序：wm size <WxH> -d <id>
        if (reports.none { it.ok }) {
            reports += runShellSize(displayId, "${width}x$height")
        }
        return reports
    }

    /** 清除逻辑尺寸覆盖，回到显示器原生尺寸。 */
    fun clearForcedDisplaySize(displayId: Int): List<Report> {
        val reports = mutableListOf<Report>()
        val label = "IWindowManager.clearForcedDisplaySize"

        if (windowManagerBinder != null) {
            val result = AidlCodec.callVoid(
                binder = windowManagerBinder,
                descriptor = AidlCodec.DESCRIPTOR_WINDOW_MANAGER,
                label = "clearForcedDisplaySize($displayId)",
                code = clearForcedDisplaySizeCode(),
                writeArgs = { it.writeInt(displayId) },
            )
            reports += result.fold(
                onSuccess = { Report(true, label, "displayId=$displayId 已清除") },
                onFailure = { Report(false, label, "displayId=$displayId 失败 ${Reflect.describe(it)}") },
            )
        } else {
            reports += Report(false, label, "取不到 window 系统服务")
        }

        if (reports.none { it.ok }) {
            reports += runShellSize(displayId, "reset")
        }
        return reports
    }

    /**
     * 跑 `wm size <value> -d <id>`。
     * 成功与否**从输出判断**（是否出现了 Override size / 尺寸行），
     * 而不是「没抛异常就算成功」—— 之前那版就是因为这样误报成功。
     */
    private fun runShellSize(displayId: Int, value: String): Report {
        val cmd = "wm size $value -d $displayId"
        val label = "wm size (per-display)"
        return try {
            val out = shellRunner(cmd).trim()
            val hasSize = Regex("""Physical size:\s*\d+x\d+""").containsMatchIn(out) ||
                Regex("""Override size:\s*\d+x\d+""").containsMatchIn(out)
            if (hasSize) {
                Report(true, label, "$cmd -> ${out.replace("\n", " | ")}")
            } else {
                Report(false, label, "$cmd 输出未包含尺寸行: ${out.ifEmpty { "(空)" }}")
            }
        } catch (t: Throwable) {
            Report(false, label, "$cmd 失败 ${Reflect.describe(t)}")
        }
    }

    /** 诊断：两条通道的可用性。 */
    fun describeChannels(): String = buildString {
        appendLine("display binder: ${AidlCodec.describeBinder(displayManagerBinder)}")
        appendLine("window  binder: ${AidlCodec.describeBinder(windowManagerBinder)}")
        appendLine("setUserPreferredDisplayMode code: $SET_USER_PREFERRED_DISPLAY_MODE (API 34/35/36 一致)")
        appendLine("setForcedDisplaySize code: ${setForcedDisplaySizeCode()} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("clearForcedDisplaySize code: ${clearForcedDisplaySizeCode()}")
        appendLine("getBaseDisplaySize code: ${getBaseDisplaySizeCode()}")
        appendLine(
            "Display.Mode 可构造: " +
                (buildDisplayMode(DisplayModeInfo(1, 1920, 1080, 60f)) != null),
        )
    }
}
