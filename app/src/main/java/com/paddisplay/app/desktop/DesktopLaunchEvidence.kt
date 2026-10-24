package com.paddisplay.app.desktop

import org.json.JSONObject

/** A successful command alone is not evidence that the desktop landed on the external display. */
object DesktopLaunchEvidence {
    fun confirmed(json: String, displayId: Int): Boolean = runCatching {
        val state = JSONObject(json)
        displayId > 0 && state.optBoolean("ok") && state.optInt("displayId", -1) == displayId &&
            state.optBoolean("desktopVisible") && state.optInt("desktopTaskId", -1) >= 0 &&
            state.optInt("desktopMode", -1) == 1 && state.optInt("desktopWidth") > 0 && state.optInt("desktopHeight") > 0
    }.getOrDefault(false)
}
