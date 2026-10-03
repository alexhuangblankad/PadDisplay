package com.paddisplay.app.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 触控板视图：手指在上面移动 → 驱动外接屏上的自绘光标。
 *
 * ## 架构（照搬已跑通的参考实现）
 *
 * 参考项目 AdaptiveScreenPlus 的做法：
 * - **光标自己画**（见 [CursorOverlay]）—— 因为系统只给真实鼠标画指针，
 *   注入的事件不会点亮它；
 * - **点击靠注入**（`input -d N tap x y`）—— 与 [CursorOverlay] 维护的坐标同一套空间；
 * - **活动范围从系统侧量**（`dumpsys window displays` 的 `cur=WxH`），
 *   不能用 `Display.getRealSize()`（会被应用兼容缩放污染）。
 *
 * ## 手感
 *
 * 手指的位移按 [sensitivity] 放大后累加到光标上（相对移动），
 * 而不是绝对映射 —— 这样手指可以反复抬起再滑动，跟笔记本触控板一致。
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 回调：请求把光标移到目标屏坐标 */
    var onMove: ((Int, Int) -> Unit)? = null

    /** 回调：请求在目标屏坐标处点击 */
    var onTap: ((Int, Int) -> Unit)? = null

    /** 回调：手指按下/抬起（用于光标视觉反馈） */
    var onPress: ((Boolean) -> Unit)? = null

    /** 灵敏度：手指位移的放大倍数 */
    var sensitivity: Float = 2.2f

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = 0f
    private var pressing = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 轻点判定阈值（像素） */
    private val tapSlop = 24f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                moved = 0f
                pressing = true
                onPress?.invoke(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                moved += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                val b = CursorOverlay.bounds()
                if (b != null) {
                    val nx = (CursorOverlay.x() + dx * sensitivity).toInt()
                    val ny = (CursorOverlay.y() + dy * sensitivity).toInt()
                    onMove?.invoke(nx, ny)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                pressing = false
                onPress?.invoke(false)
                // 位移很小 = 一次轻点 → 点击
                val quick = System.currentTimeMillis() - downTime < 400
                if (moved < tapSlop && quick && CursorOverlay.isOn()) {
                    onTap?.invoke(CursorOverlay.x(), CursorOverlay.y())
                }
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressing = false
                onPress?.invoke(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        // 背景
        paint.color = if (pressing) Color.parseColor("#FF2A3138") else Color.parseColor("#FF1B2026")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        // 边框
        paint.color = Color.parseColor("#FF3A424B")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRoundRect(rect, 24f, 24f, paint)

        // 提示文字
        paint.color = Color.parseColor("#FF8A94A0")
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 42f
        canvas.drawText("在此滑动 = 移动外接屏光标", width / 2f, height / 2f - 12f, paint)
        canvas.drawText("轻点 = 单击", width / 2f, height / 2f + 46f, paint)

        // 光标坐标读数
        val b = CursorOverlay.bounds()
        if (b != null) {
            paint.color = Color.parseColor("#FF6FD3C7")
            paint.textSize = 36f
            canvas.drawText(
                "光标 ${CursorOverlay.x()}, ${CursorOverlay.y()}   范围 ${b[0]}×${b[1]}",
                width / 2f,
                height - 40f,
                paint,
            )
        }
    }
}
