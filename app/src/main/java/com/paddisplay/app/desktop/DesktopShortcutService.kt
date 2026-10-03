package com.paddisplay.app.desktop

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional global F9 shortcut only; no nodes, mouse handling, gestures or remote-client modifier changes. */
class DesktopShortcutService : AccessibilityService() {
    private var consumed = false
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { consumed = false }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_F9) return false
        if (event.action == KeyEvent.ACTION_UP && consumed) { consumed = false; return true }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (consumed) return true
        if (!DesktopState.state.value.running || !EscapeKeyPolicy.matches(event.keyCode, event.metaState)) return false
        if (event.repeatCount != 0) return false
        val sent = runCatching { DesktopService.send(this, "escape") }.isSuccess
        consumed = sent
        return sent
    }
}
