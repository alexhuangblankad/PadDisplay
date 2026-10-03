package com.paddisplay.app.system

import android.os.IBinder
import android.view.InputDevice
import android.view.MotionEvent

/**
 * 指针事件注入 —— 触控板方案的地基。
 *
 * ## 为什么不能只用 `input` 命令
 *
 * 参考项目 AdaptiveScreenPlus 的 `TouchInject` 实测记录：
 *
 * > 老路 `input` 命令每注一个事件都要起进程装 JVM，实测**一条 47ms**
 * > （串行 21 次/秒；4 路并发也才 44 次/秒），从根上追不上内屏 120Hz / 副屏 60Hz，
 * > **拖动才会一跳一跳**；换成 `IInputManager.injectInputEvent` 后
 * > 事件率到几百/秒、单次延迟降到 1ms 级。
 *
 * 所以：低频点击可以用 `input`，**拖动必须走 injectInputEvent**。
 *
 * ## 关键点
 *
 * 1. `MotionEvent.setDisplayId()` 是隐藏 API —— 必须反射，它决定事件进哪块屏。
 * 2. 注入要 `INJECT_EVENTS` 权限（shell / system 才有），所以经 Shizuku 的
 *    UserService（uid=2000）发出，与 `adb shell input` 同一身份。
 * 3. 坐标必须与客户端自绘光标用**同一套空间**（`dumpsys window displays` 的 `cur=WxH`），
 *    否则又是"看得见的光标"和"实际点击点"分离。
 */
class PointerInjector(
    private val inputManagerBinder: IBinder?,
    private val shellRunner: (String) -> String,
) {

    companion object {
        const val DESCRIPTOR_INPUT_MANAGER = "android.hardware.input.IInputManager"

        /** AOSP `IInputManager.aidl` 解析 + 真实编译验证：API 36 = 11。 */
        private const val INJECT_INPUT_EVENT = 11

        /** `IInputManager.INJECT_INPUT_EVENT_MODE_ASYNC`：注完就走，不等目标应用处理完。 */
        private const val MODE_ASYNC = 0

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        /** `MotionEvent.BUTTON_PRIMARY` / `BUTTON_SECONDARY` */
        const val BUTTON_LEFT = MotionEvent.BUTTON_PRIMARY
        const val BUTTON_RIGHT = MotionEvent.BUTTON_SECONDARY

        private const val SOURCE_MOUSE = InputDevice.SOURCE_MOUSE

        private var downTime = 0L
    }

    data class Report(val ok: Boolean, val detail: String)

    /** 注入是否可用（binder 在 + 反射拿到 setDisplayId）。 */
    fun ready(): Boolean = inputManagerBinder != null && setDisplayIdMethod() != null

    private var cachedSetDisplay: java.lang.reflect.Method? = null
    private var setDisplayResolved = false

    private fun setDisplayIdMethod(): java.lang.reflect.Method? {
        if (!setDisplayResolved) {
            setDisplayResolved = true
            cachedSetDisplay = Reflect.findMethod(MotionEvent::class.java, "setDisplayId", Int::class.javaPrimitiveType)
        }
        return cachedSetDisplay
    }

    /**
     * 注入一次指针按下或抬起。
     *
     * @param button [BUTTON_LEFT] 或 [BUTTON_RIGHT]
     */
    fun injectDownUp(displayId: Int, action: Int, x: Int, y: Int, button: Int): Report {
        val binder = inputManagerBinder
            ?: return Report(false, "取不到 IInputManager（需要 Shizuku）")
        val now = android.os.SystemClock.uptimeMillis()
        if (action == ACTION_DOWN) downTime = now

        // obtain(downTime, eventTime, action, x, y, metaState)
        // 按钮通过 setButtonState 表达；公共 API 只保证 setSource。
        val event = MotionEvent.obtain(
            downTime,
            now,
            action,
            x.toFloat(),
            y.toFloat(),
            0,
        )
        return try {
            event.source = SOURCE_MOUSE
            // setButtonState 是隐藏 API，用反射设置（决定左键还是右键）
            runCatching {
                Reflect.findMethod(MotionEvent::class.java, "setButtonState", Int::class.javaPrimitiveType)
                    ?.invoke(event, if (action == ACTION_DOWN) button else 0)
            }
            // setDisplayId 是隐藏 API，决定事件进哪块屏
            setDisplayIdMethod()?.invoke(event, displayId)
            doInject(binder, event)
        } finally {
            event.recycle()
        }
    }

    /** 注入一次指针移动（拖动时高频调用）。 */
    fun injectMove(displayId: Int, x: Int, y: Int): Report {
        val binder = inputManagerBinder
            ?: return Report(false, "取不到 IInputManager（需要 Shizuku）")
        val now = android.os.SystemClock.uptimeMillis()
        if (downTime == 0L) downTime = now
        val event = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), 0)
        return try {
            event.source = SOURCE_MOUSE
            setDisplayIdMethod()?.invoke(event, displayId)
            doInject(binder, event)
        } finally {
            event.recycle()
        }
    }

    private fun doInject(binder: IBinder, event: MotionEvent): Report = AidlCodec.call(
        binder = binder,
        descriptor = DESCRIPTOR_INPUT_MANAGER,
        label = "injectInputEvent",
        code = INJECT_INPUT_EVENT,
        writeArgs = { data ->
            event.writeToParcel(data, 0)
            data.writeInt(MODE_ASYNC)
        },
        readReply = { it.readInt() != 0 },
    ).fold(
        onSuccess = { accepted ->
            if (accepted) Report(true, "已注入") else Report(false, "系统拒绝注入（可能缺 INJECT_EVENTS 权限）")
        },
        onFailure = { Report(false, Reflect.describe(it)) },
    )

    /**
     * 兜底：用 `input` 命令注入一次点击。
     * 低频点击够用（约 47ms），拖动不行。
     */
    fun fallbackTap(displayId: Int, x: Int, y: Int): Report {
        val cmd = "/system/bin/input -d $displayId tap $x $y"
        val out = runCatching { shellRunner(cmd) }.getOrElse { return Report(false, it.message ?: "执行失败") }
        val ok = out.isBlank() || !out.contains("Error", ignoreCase = true)
        return Report(ok, "$cmd ${out.trim().take(120)}")
    }

    fun describe(): String = buildString {
        appendLine("input binder: ${AidlCodec.describeBinder(inputManagerBinder)}")
        appendLine("injectInputEvent code: $INJECT_INPUT_EVENT")
        appendLine("setDisplayId 反射可用: ${setDisplayIdMethod() != null}")
        appendLine("ready: ${ready()}")
    }
}
