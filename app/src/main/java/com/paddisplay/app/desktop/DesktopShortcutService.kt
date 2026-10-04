package com.paddisplay.app.desktop

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional F9 / Alt+Shift rescue; no nodes, mouse handling or gesture simulation. */
class DesktopShortcutService : AccessibilityService() {
    private var consumed = false
    private var chordHeld = false
    private val heldModifiers = mutableSetOf<Int>()
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { consumed = false; chordHeld = false; heldModifiers.clear() }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT)) {
            if (event.action == KeyEvent.ACTION_UP) { chordHeld = false; heldModifiers.remove(event.keyCode) }
            if (event.action == KeyEvent.ACTION_DOWN) heldModifiers.add(event.keyCode)
            val hasAlt = heldModifiers.any { it == KeyEvent.KEYCODE_ALT_LEFT || it == KeyEvent.KEYCODE_ALT_RIGHT }
            val hasShift = heldModifiers.any { it == KeyEvent.KEYCODE_SHIFT_LEFT || it == KeyEvent.KEYCODE_SHIFT_RIGHT }
            val chordMeta = event.metaState or (if (hasAlt) KeyEvent.META_ALT_ON else 0) or (if (hasShift) KeyEvent.META_SHIFT_ON else 0)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !chordHeld &&
                DesktopState.state.value.running && EscapeKeyPolicy.altShiftMatches(event.keyCode,chordMeta)) {
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
