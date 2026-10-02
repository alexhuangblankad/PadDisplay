package com.paddisplay.app.display

/**
 * “最佳分辨率”选择器。
 *
 * 按用户与 ChatGPT 确认过的优先级：
 *   1. 优先选择物理像素面积最大的有效模式
 *   2. 同分辨率下，**默认优先 60 Hz**（USB-C DP Alt Mode 带宽/线材/转接器兼容性最好）
 *   3. 如果没有 60 Hz，回退到最接近 60 Hz 的模式（略高优先，其次略低）
 *   4. 绝不动内屏 — 只有外接屏参与选择
 *
 * 例：3840×2160@120 / 3840×2160@60 / 1920×1080@120
 *     -> 自动选择 3840×2160@60
 *     手动依然可以选择 3840×2160@120
 */
object DisplayModeSelector {

    /** 自动模式偏好的刷新率。 */
    const val PREFERRED_REFRESH_HZ = 60f

    /**
     * 选出“自动 / 最佳”模式。
     * @return null 表示该显示器没有可用的 supportedModes。
     */
    fun selectBest(display: DisplaySnapshot): DisplayModeInfo? =
        selectBest(display.supportedModes)

    fun selectBest(modes: List<DisplayModeInfo>): DisplayModeInfo? {
        if (modes.isEmpty()) return null
        val maxPixels = modes.maxOf { it.pixels }
        val largest = modes.filter { it.pixels == maxPixels }
        return pickRefresh(largest, PREFERRED_REFRESH_HZ)
    }

    /** 按“原生/最高分辨率 + 用户指定的刷新率”挑选模式。 */
    fun selectBestForRefresh(modes: List<DisplayModeInfo>, refreshHz: Float): DisplayModeInfo? {
        if (modes.isEmpty()) return null
        val maxPixels = modes.maxOf { it.pixels }
        val largest = modes.filter { it.pixels == maxPixels }
        return pickRefresh(largest, refreshHz)
    }

    /** 在给定候选集合里挑最贴近 [targetHz] 的刷新率。 */
    fun pickRefresh(candidates: List<DisplayModeInfo>, targetHz: Float): DisplayModeInfo? {
        if (candidates.isEmpty()) return null

        // 1) 精确命中（容忍浮点误差）
        candidates.firstOrNull { kotlin.math.abs(it.refreshRate - targetHz) < 0.01f }?.let { return it }

        // 2) 高于目标的里面挑最小的（60 缺失时，61~75 通常比 30 好）
        val above = candidates.filter { it.refreshRate > targetHz }.minByOrNull { it.refreshRate }
        // 3) 低于目标的里面挑最大的
        val below = candidates.filter { it.refreshRate < targetHz }.maxByOrNull { it.refreshRate }

        return when {
            above != null && below != null -> {
                // 谁离目标更近就用谁；距离一样时选更高的
                val dAbove = above.refreshRate - targetHz
                val dBelow = targetHz - below.refreshRate
                if (dAbove <= dBelow) above else below
            }
            above != null -> above
            else -> below
        }
    }

    /**
     * 把 supportedModes 按“分辨率优先、刷新率次之”排序，供 UI 列表展示。
     */
    fun sortForDisplay(modes: List<DisplayModeInfo>): List<DisplayModeInfo> =
        modes.sortedWith(
            compareByDescending<DisplayModeInfo> { it.pixels }
                .thenByDescending { it.physicalWidth }
                .thenByDescending { it.refreshRate },
        )

    /** 判断某个候选模式是否是“当前模式”。 */
    fun isSameMode(a: DisplayModeInfo?, b: DisplayModeInfo?): Boolean {
        if (a == null || b == null) return false
        return a.physicalWidth == b.physicalWidth &&
            a.physicalHeight == b.physicalHeight &&
            kotlin.math.abs(a.refreshRate - b.refreshRate) < 0.01f
    }
}
