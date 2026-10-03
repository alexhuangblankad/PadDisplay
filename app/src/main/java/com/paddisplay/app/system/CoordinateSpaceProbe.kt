package com.paddisplay.app.system

/**
 * 目标屏的「注入坐标空间」测量 —— 解决光标显示与实际点击不一致的根因。
 *
 * ## 为什么必须从系统侧量，而不能问 Display API
 *
 * 参考项目 AdaptiveScreenPlus 的 `CursorOverlay` / `ExtScreen` 里踩过并记录了这件事：
 *
 * > ⚠ **范围（bounds）必须从系统那一侧量，不能问 Display**：
 * > display context 的 `getResources().getDisplayMetrics()` 会被**本应用的兼容缩放**污染 ——
 * > 实测内屏明明是 1920×1080，它报 1496×1242，于是光标被夹在 x≤1495，
 * > 右边 424px 永远够不到。
 * >
 * > ⚠ Display 的那几个 API 都别用：`getRealSize/getMetrics` 会串应用缩放，
 * > `getMode()` 又不认 `wm size` 覆盖。
 *
 * 唯一可靠的来源是 WindowManager：`dumpsys window displays` 里那块屏的
 * **`cur=WxH`**，它已经算上旋转与 `wm size` 覆盖，
 * 并且与 `input -d N tap x y`、`screencap` 用的是**同一套坐标系**。
 *
 * ## 对本项目的意义
 *
 * 这就是"接上显示器后鼠标飘逸、显示位置和点击位置不一致"的根因类别：
 * **坐标空间取错了源**。绑定物理鼠标只是把问题暴露出来（事件按一个空间算、
 * 光标按另一个空间画）。
 *
 * 因此本项目：
 * - 不再依赖 `Display.getRealSize()/getMetrics()` 来定坐标；
 * - 一切注入类操作（`input -d N ...`）都先取这里的 `cur=WxH` 作为空间基准；
 * - 并把两侧空间都显示出来，矛盾时能一眼看出。
 */
class CoordinateSpaceProbe(
    private val shellRunner: (String) -> String,
) {

    data class Space(
        val displayId: Int,
        /** WindowManager 眼里的逻辑尺寸（= 注入/截图坐标系） */
        val width: Int,
        val height: Int,
        val raw: String,
    ) {
        val label: String get() = "${width}x$height"
    }

    /**
     * 解析 `dumpsys window displays` 中指定屏的 `cur=WxH`。
     *
     * dumpsys 的输出是"一块屏一段"，所以要从 `mDisplayId=N` 往后取到下一块屏为止，
     * 在其中找**第一个** `cur=`。
     */
    fun injectionSpace(displayId: Int): Space? {
        val dump = runCatching { shellRunner("/system/bin/dumpsys window displays") }.getOrNull()
            ?: return null
        return parseInjectionSpace(dump, displayId)
    }

    /** 纯解析逻辑，便于单测与诊断。 */
    fun parseInjectionSpace(dump: String, displayId: Int): Space? {
        val lines = dump.lines()
        // 定位 "mDisplayId=N" 起始，直到下一个 "mDisplayId="
        var start = -1
        for (i in lines.indices) {
            if (Regex("""\bmDisplayId=$displayId\b""").containsMatchIn(lines[i])) {
                start = i
                break
            }
        }
        if (start < 0) return null
        val segment = StringBuilder()
        for (i in start until lines.size) {
            if (i > start && lines[i].contains("mDisplayId=")) break
            segment.appendLine(lines[i])
        }
        val seg = segment.toString()
        val m = Regex("""cur=(\d+)x(\d+)""").find(seg)
        val space = if (m != null) {
            Space(
                displayId = displayId,
                width = m.groupValues[1].toIntOrNull() ?: 0,
                height = m.groupValues[2].toIntOrNull() ?: 0,
                raw = m.value,
            )
        } else {
            // 有些版本用 init= / app= 表示
            val init = Regex("""init=(\d+)x(\d+)""").find(seg) ?: return null
            Space(
                displayId = displayId,
                width = init.groupValues[1].toIntOrNull() ?: 0,
                height = init.groupValues[2].toIntOrNull() ?: 0,
                raw = init.value,
            )
        }
        return space.takeIf { it.width > 0 && it.height > 0 }
    }

    /**
     * 把注入坐标夹到目标屏空间内。
     * 参考项目正是因为空间取错而"右边够不到"，这里做显式夹取并给出提示。
     */
    fun clamp(space: Space, x: Int, y: Int): Triple<Int, Int, Boolean> {
        val cx = x.coerceIn(0, (space.width - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (space.height - 1).coerceAtLeast(0))
        return Triple(cx, cy, cx != x || cy != y)
    }

    /**
     * 在指定屏上注入一次点击。
     *
     * 用系统自带的 `input -d <id> tap x y`：
     * 它与 [injectionSpace] 的 `cur=WxH` 是同一套坐标系。
     *
     * 注意（参考项目实测）：`input` 每条命令要起一个进程装 JVM，约 47ms，
     * 想做到流畅拖动需要走 `IInputManager.injectInputEvent`。
     * 点击/低频操作走 `input` 足够，且不依赖额外权限。
     */
    fun tap(displayId: Int, x: Int, y: Int): Pair<Boolean, String> {
        val space = injectionSpace(displayId)
            ?: return false to "取不到 displayId=$displayId 的坐标空间（dumpsys window displays 里没有 cur=）"
        val (cx, cy, clamped) = clamp(space, x, y)
        val cmd = "/system/bin/input -d $displayId tap $cx $cy"
        val out = runCatching { shellRunner(cmd) }.getOrElse { return false to "执行失败: ${it.message}" }
        val ok = out.isBlank() || !out.contains("Error", ignoreCase = true)
        return ok to buildString {
            append("空间=${space.label}（来源 ${space.raw}）")
            if (clamped) append("，坐标已从 ($x,$y) 夹取到 ($cx,$cy)")
            append("；命令: $cmd")
            if (out.isNotBlank()) append("；输出: ${out.trim().take(200)}")
        }
    }

    /** 诊断：把两块屏的"系统坐标空间"与"Display API 读数"并排列出来，暴露污染。 */
    fun describe(displayIds: List<Int>): String = buildString {
        val dump = runCatching { shellRunner("/system/bin/dumpsys window displays") }.getOrNull()
        if (dump == null) {
            appendLine("dumpsys window displays 取不到（需要 Shizuku shell 权限）")
            return@buildString
        }
        appendLine("=== 坐标空间对照（注入空间 vs Display API）===")
        displayIds.forEach { id ->
            appendLine()
            appendLine("displayId = $id")
            val space = parseInjectionSpace(dump, id)
            appendLine("  注入空间（dumpsys window displays 的 cur=）: ${space?.label ?: "(解析不到)"}  ${space?.raw ?: ""}")
            appendLine("  —— 这一套才是 input -d N / screencap 用的坐标系 ——")
        }
        appendLine()
        appendLine("说明：Display.getRealSize()/getMetrics() 会被「应用兼容缩放」污染，")
        appendLine("不要用它们当注入坐标。参考项目实测内屏 1920x1080 被报成 1496x1242。")
    }
}
