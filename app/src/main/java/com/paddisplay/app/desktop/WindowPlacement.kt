package com.paddisplay.app.desktop

/** Coordinates always refer to the current WindowManager viewport, not Display metrics. */
object WindowPlacement {
    fun bounds(width: Int, height: Int, barHeight: Int, placement: String, current: IntArray?): IntArray {
        require(width >= 320 && height >= 240)
        val usableHeight = (height - barHeight.coerceIn(0, height - 120)).coerceAtLeast(120)
        val old = current ?: intArrayOf(width / 6, height / 8, width * 5 / 6, height * 7 / 8)
        require(old.size == 4)
        var l = old[0]; var t = old[1]
        var w = (old[2] - l).coerceIn(160, width)
        var h = (old[3] - t).coerceIn(120, usableHeight)
        when (placement) {
            "left" -> { l = 0; t = 0; w = width / 2; h = usableHeight }
            "right" -> { l = width / 2; t = 0; w = width - l; h = usableHeight }
            "fill" -> { l = 0; t = 0; w = width; h = usableHeight }
            "center" -> { w = width * 2 / 3; h = usableHeight * 3 / 4; l = (width - w) / 2; t = (usableHeight - h) / 2 }
            "up" -> t -= 64
            "down" -> t += 64
            "moveLeft" -> l -= 64
            "moveRight" -> l += 64
            "larger" -> { w += 128; h += 96 }
            "smaller" -> { w -= 128; h -= 96 }
            else -> error("未知窗口布局")
        }
        w = w.coerceIn(160, width); h = h.coerceIn(120, usableHeight)
        l = l.coerceIn(0, width - w); t = t.coerceIn(0, usableHeight - h)
        return intArrayOf(l, t, l + w, t + h)
    }
}
