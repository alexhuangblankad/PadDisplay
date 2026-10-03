package com.paddisplay.app.system

import kotlinx.coroutines.delay

/** UI density is independent of the display's physical timing and resolution. */
class DisplayDensityController(private val shell: suspend (String) -> String) {
    data class State(val physical: Int, val override: Int?) {
        val effective: Int get() = override ?: physical
    }

    suspend fun read(displayId: Int): State {
        require(displayId > 0) { "只允许调整外接屏" }
        val output = shell("/system/bin/wm density -d $displayId")
        val physical = Regex("(?m)^Physical density:\\s*(\\d+)\\s*$")
            .find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("无法读取外屏 DPI：$output")
        val override = Regex("(?m)^Override density:\\s*(\\d+)\\s*$")
            .find(output)?.groupValues?.get(1)?.toIntOrNull()
        require(physical > 0) { "外屏 DPI 无效：$output" }
        return State(physical, override)
    }

    suspend fun write(displayId: Int, override: Int?): State {
        require(displayId > 0)
        require(override == null || override in 72..1280) { "DPI 超出可调整范围" }
        val output = shell("/system/bin/wm density ${override ?: "reset"} -d $displayId")
        var actual = read(displayId)
        repeat(10) {
            if (actual.override == override) return actual
            delay(100)
            actual = read(displayId)
        }
        error("外屏 DPI 读回不匹配：期望=$override，实际=${actual.override}；$output")
    }
}
