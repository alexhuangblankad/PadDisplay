package com.paddisplay.app.desktop

import android.view.KeyEvent

object EscapeKeyPolicy {
    fun matches(keyCode: Int, metaState: Int): Boolean = keyCode == KeyEvent.KEYCODE_F9 &&
        metaState and (KeyEvent.META_ALT_MASK or KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK or KeyEvent.META_SHIFT_MASK) == 0
}
