package com.paddisplay.app.desktop

import android.view.KeyEvent

object EscapeKeyPolicy {
    fun altShiftMatches(keyCode: Int, metaState: Int): Boolean =
        keyCode in listOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT) &&
            metaState and KeyEvent.META_ALT_MASK != 0 && metaState and KeyEvent.META_SHIFT_MASK != 0 &&
            metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK) == 0
    fun matches(keyCode: Int, metaState: Int): Boolean = keyCode == KeyEvent.KEYCODE_F9 &&
        metaState and (KeyEvent.META_ALT_MASK or KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK or KeyEvent.META_SHIFT_MASK) == 0
}
