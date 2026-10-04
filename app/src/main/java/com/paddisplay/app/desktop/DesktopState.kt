package com.paddisplay.app.desktop

import com.paddisplay.app.system.SystemDisplayService
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class DesktopTask(val id: Int, val packageName: String, val mode: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int, val visible: Boolean = true,
    val configuration: String = "")
data class DesktopSnapshot(
    val running: Boolean = false, val displayId: Int = -1,
    val apps: List<SystemDisplayService.LaunchableApp> = emptyList(),
    val tasks: List<DesktopTask> = emptyList(), val message: String = "主机模式未启动",
    val overlayReady: Boolean = false, val favorites: Set<String> = emptySet(),
    val taskError: String? = null,
    val activeTaskId: Int = -1,
    val desktopVisible: Boolean = false,
)
object DesktopState {
    val state = MutableStateFlow(DesktopSnapshot())
    fun parseTasks(json: String): List<DesktopTask> {
        val root = JSONObject(json)
        check(root.optBoolean("ok")) { root.optString("error", "任务读取失败") }
        val rows = root.getJSONArray("tasks")
        return (0 until rows.length()).map { index ->
            val t = rows.getJSONObject(index)
            DesktopTask(t.getInt("id"), t.getString("package"), t.getInt("mode"),
                t.getInt("left"), t.getInt("top"), t.getInt("right"), t.getInt("bottom"), t.optBoolean("visible", true), t.optString("configuration"))
        }
    }
}
