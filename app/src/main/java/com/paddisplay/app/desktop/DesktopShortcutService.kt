package com.paddisplay.app.desktop

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional F9 / Alt+Shift rescue; no nodes, mouse handling or gesture simulation. */
class DesktopShortcutService : AccessibilityService() {
    private var consumed = false
    private var chordHeld = false
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { consumed = false; chordHeld = false }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT)) {
            if (event.action == KeyEvent.ACTION_UP) chordHeld = false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !chordHeld &&
                DesktopState.state.value.running && EscapeKeyPolicy.altShiftMatches(event.keyCode,event.metaState)) {
                chordHeld = runCatching { DesktopService.send(this,"escape") }.isSuccess
            }
            // Both modifier down/up events pass through; consuming only one half can
            // leave a remote client with a stuck Alt or Shift.
            return false
        }
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
