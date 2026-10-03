package com.paddisplay.app.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.paddisplay.app.system.CoordinateSpaceProbe

/**
 * 自绘光标浮层 —— 挂在外接屏上的虚拟鼠标指针。
 *
 * ## 为什么必须自己画
 *
 * 参考项目 AdaptiveScreenPlus 的 `CursorOverlay` 注释说得很清楚：
 *
 * > **系统只给真实鼠标（USB / uinput）画指针，应用侧注入的事件不会点亮它**，
 * > 非 root 没有接口 —— 只能自己挂一层：拿目标屏的 display context 加一个
 * > `TYPE_APPLICATION_OVERLAY` 悬浮窗，需要「显示在其他应用上层」权限。
 *
 * 也就是说：`IInputManager.injectInputEvent` / `input -d N tap` 这类注入
 * **不会让系统显示指针**，所以必须自己画一个，否则用户根本看不到鼠标在哪。
 *
 * ## 坐标系必须与注入坐标一致
 *
 * 窗口的 `lp.x/lp.y`、注入命令的落点、以及那块屏的画面，**三者是同一套坐标**：
 * 目标屏旋转后的逻辑坐标。所以这里维护的就是"注入坐标"，
 * 移动光标与注入点击用的是同一个数值，天然对齐。
 *
 * ⚠ 但**活动范围必须从系统侧量**（`dumpsys window displays` 的 `cur=WxH`），
 * 不能问 `Display.getRealSize()/getMetrics()` —— 它们会被本应用的兼容缩放污染
 * （参考项目实测：内屏 1920×1080 被报成 1496×1242，右边 424px 永远够不到）。
 */
object CursorOverlay {

    private const val TAG = "PadDisplay/Cursor"

    /** 光标直径（dp） */
    private const val SIZE_DP = 28f

    private var view: View? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var sizePx = 0
    private var boundW = 0
    private var boundH = 0
    private var cx = -1
    private var cy = -1
    private var hot = false
    private var appCtx: Context? = null
    private var targetDisplay = -1
    private var probe: CoordinateSpaceProbe? = null

    fun isOn(): Boolean = view != null

    fun x(): Int = cx
    fun y(): Int = cy

    /** 活动范围（= 注入坐标空间）。null 表示没挂上。 */
    fun bounds(): IntArray? = if (view == null) null else intArrayOf(boundW, boundH)

    /**
     * 把光标挂到指定屏上。
     *
     * @param shellRunner 用于从 `dumpsys window displays` 量真实活动范围
     */
    fun show(app: Context, displayId: Int, shellRunner: (String) -> String): Boolean {
        if (view != null) return true
        return try {
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(displayId)
            if (display == null) {
                Log.w(TAG, "屏 $displayId 不存在，挂不了光标")
                return false
            }
            val dc = app.createDisplayContext(display)
            val w = dc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 密度是可信的（万一被缩放带跑就退回 2.0，顶多光标大小不理想）
            var density = dc.resources.displayMetrics.density
            if (density < 1f || density > 6f) density = 2f
            sizePx = maxOf(24, Math.round(SIZE_DP * density))

            // 临时范围；随后用系统侧的量法复核
            boundW = dc.resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: 1920
            boundH = dc.resources.displayMetrics.heightPixels.takeIf { it > 0 } ?: 1080
            if (cx < 0) {
                cx = boundW / 2
                cy = boundH / 2
            }
            cx = cx.coerceIn(0, maxOf(0, boundW - 1))
            cy = cy.coerceIn(0, maxOf(0, boundH - 1))

            val p = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            p.gravity = Gravity.TOP or Gravity.LEFT
            p.setTitle("paddisplay-cursor")
            p.x = cx - sizePx / 2
            p.y = cy - sizePx / 2

            val v = CursorView(dc, sizePx)
            w.addView(v, p)

            view = v
            wm = w
            lp = p
            appCtx = app.applicationContext
            targetDisplay = displayId
            probe = CoordinateSpaceProbe(shellRunner)

            Log.i(TAG, "光标已挂到屏 $displayId（临时范围 ${boundW}×$boundH，直径 ${sizePx}px）")
            calibrate() // 立刻用系统侧的量法复核
            true
        } catch (t: Throwable) {
            Log.e(TAG, "挂光标失败", t)
            view = null
            wm = null
            lp = null
            false
        }
    }

    /**
     * 用系统侧的量法复核活动范围。
     *
     * 这一步是"光标到不了右边"那类 bug 的解药：
     * `getRealSize()/getMetrics()` 会被应用兼容缩放污染，
     * 只有 `dumpsys window displays` 的 `cur=WxH` 才是注入/截图共用的坐标空间。
     */
    fun calibrate() {
        val p = probe ?: return
        val space = p.injectionSpace(targetDisplay) ?: return
        if (space.width <= 0 || space.height <= 0) return
        if (space.width == boundW && space.height == boundH) return
        Log.i(TAG, "活动范围纠正：${boundW}×$boundH -> ${space.width}×${space.height}（来源 ${space.raw}）")
        boundW = space.width
        boundH = space.height
        cx = cx.coerceIn(0, maxOf(0, boundW - 1))
        cy = cy.coerceIn(0, maxOf(0, boundH - 1))
        move(cx, cy)
    }

    /** 移光标（目标屏坐标，越界自动夹住）。 */
    fun move(x: Int, y: Int) {
        val v = view ?: return
        val w = wm ?: return
        val p = lp ?: return
        val nx = x.coerceIn(0, maxOf(0, boundW - 1))
        val ny = y.coerceIn(0, maxOf(0, boundH - 1))
        if (nx == cx && ny == cy) return
        cx = nx
        cy = ny
        p.x = cx - sizePx / 2
        p.y = cy - sizePx / 2
        try {
            w.updateViewLayout(v, p)
        } catch (t: Throwable) {
            Log.w(TAG, "移光标失败: $t")
        }
    }

    /** 按下/抬起 → 视觉反馈。 */
    fun setHot(h: Boolean) {
        if (view == null || hot == h) return
        hot = h
        view?.invalidate()
    }

    /** 撤掉光标。 */
    fun hide() {
        val v = view ?: return
        try {
            wm?.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "撤光标失败: $t")
        }
        view = null
        wm = null
        lp = null
        hot = false
    }

    /** 光标外观：一个带描边的圆点，按下时变实心。 */
    private class CursorView(context: Context, private val size: Int) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, size / 10f)
        }

        override fun onDraw(canvas: Canvas) {
            val r = size / 2f
            if (hot) {
                canvas.drawCircle(r, r, r * 0.45f, fill)
            } else {
                fill.alpha = 220
                canvas.drawCircle(r, r, r * 0.6f, fill)
            }
            canvas.drawCircle(r, r, r * 0.75f, ring)
        }
    }
}
