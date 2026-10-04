package com.paddisplay.app.desktop

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Small native caption and resize grip only. The app's content and screen edges receive system input. */
class NativeWindowDecorations(private val context: Context, private val wm: WindowManager,
    private val onAction: (String, Int) -> Unit, private val onBounds: (Int, IntArray) -> Unit) {
    private val views = mutableListOf<View>()
    var dragging = false
        private set
    private val scale = context.resources.displayMetrics.density
    private fun dp(n: Int) = (n * scale).toInt()
    private fun params(w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; this.x = x; this.y = y
            // Task bounds use full-display coordinates; do not apply status-bar offsets again.
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }

    fun show(state: DesktopSnapshot, width: Int, height: Int, dark: Boolean) {
        state.tasks.filter { it.mode == 5 && it.visible && it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height && it.right-it.left >= 160 && it.bottom-it.top >= 120 }.take(12).reversed().forEach { task ->
            val foreground = if (dark) Color.rgb(236, 239, 246) else Color.rgb(36, 42, 54)
            val captionHeight = dp(34)
            val edge = dp(24)
            val captionWidth = (task.right-task.left).coerceAtMost(width-edge*2)
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.rgb(40, 46, 58) else Color.rgb(245, 246, 250)); cornerRadius = dp(10).toFloat()
                }
            }
            val barParams = params(captionWidth, captionHeight, task.left.coerceIn(edge,width-captionWidth-edge), (task.top - captionHeight).coerceAtLeast(dp(8)))
            listOf(Triple("●", Color.rgb(237, 98, 91), "close"), Triple("⌂", Color.rgb(234, 184, 61), "home"), Triple("●", Color.rgb(61, 187, 103), "fullscreen")).forEach { (label, tint, action) ->
                bar.addView(TextView(context).apply {
                    text = label; textSize = 15f; setTextColor(tint); gravity = Gravity.CENTER
                    contentDescription = when(action) { "close" -> "关闭窗口"; "home" -> "返回桌面"; else -> "全屏" }
                    setOnClickListener { onAction(action, task.id) }
                }, LinearLayout.LayoutParams(dp(32), captionHeight))
            }
            val label = state.apps.firstOrNull { it.packageName == task.packageName }?.label ?: task.packageName.substringAfterLast('.')
            val title = TextView(context).apply { text = label; setTextColor(foreground); textSize = 12f; maxLines = 1; gravity = Gravity.CENTER_VERTICAL; contentDescription = "$label · 拖动移动窗口" }
            bar.addView(title, LinearLayout.LayoutParams(0, captionHeight, 1f))
            var startX = 0f; var startY = 0f; var pending = intArrayOf(task.left, task.top, task.right, task.bottom)
            title.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dragging = true; startX = event.rawX; startY = event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        val w = (task.right-task.left).coerceIn(160, width)
                        val h = (task.bottom-task.top).coerceIn(120, height)
                        val l = (task.left + event.rawX-startX).toInt().coerceIn(0, width-w)
                        val t = (task.top + event.rawY-startY).toInt().coerceIn(0, height-h)
                        pending = intArrayOf(l,t,l+w,t+h)
                        barParams.x = l.coerceIn(edge,width-captionWidth-edge); barParams.y = (t-captionHeight).coerceAtLeast(dp(8)); wm.updateViewLayout(bar,barParams)
                    }
                    MotionEvent.ACTION_UP -> { dragging = false; view.performClick(); onBounds(task.id,pending) }
                    MotionEvent.ACTION_CANCEL -> { dragging = false; barParams.x=task.left.coerceIn(edge,width-captionWidth-edge); barParams.y=(task.top-captionHeight).coerceAtLeast(dp(8)); wm.updateViewLayout(bar,barParams) }
                }; true
            }
            wm.addView(bar, barParams); views += bar
            val grip = TextView(context).apply {
                text = "◢"; textSize = 20f; setTextColor(foreground); gravity = Gravity.CENTER; contentDescription = "$label · 拖动调整尺寸"
                background = GradientDrawable().apply { setColor(if (dark) Color.rgb(40,46,58) else Color.rgb(245,246,250)); cornerRadius=dp(8).toFloat() }
            }
            val gripParams = params(dp(28),dp(28),(task.right-dp(28)).coerceIn(edge,width-dp(28)-edge),(task.bottom-dp(28)).coerceAtLeast(0))
            var resizeX=0f; var resizeY=0f; var resized=intArrayOf(task.left,task.top,task.right,task.bottom)
            grip.setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dragging = true; resizeX=event.rawX; resizeY=event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        val r=(task.right+event.rawX-resizeX).toInt().coerceIn((task.left+160).coerceAtMost(width),width)
                        val b=(task.bottom+event.rawY-resizeY).toInt().coerceIn((task.top+120).coerceAtMost(height),height)
                        resized=intArrayOf(task.left,task.top,r,b)
                        gripParams.x=(r-dp(28)).coerceIn(edge,width-dp(28)-edge);gripParams.y=b-dp(28);wm.updateViewLayout(grip,gripParams)
                    }
                    MotionEvent.ACTION_UP -> { dragging = false; view.performClick(); onBounds(task.id,resized) }
                    MotionEvent.ACTION_CANCEL -> { dragging = false; gripParams.x=(task.right-dp(28)).coerceIn(edge,width-dp(28)-edge);gripParams.y=task.bottom-dp(28);wm.updateViewLayout(grip,gripParams) }
                };true
            }
            wm.addView(grip,gripParams); views += grip
        }
    }
    fun clear() { views.forEach { runCatching { wm.removeViewImmediate(it) } }; views.clear() }
}
