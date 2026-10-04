package com.paddisplay.app.system

import android.content.ComponentName
import android.app.ActivityOptions
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Native tasks on physical displays. No virtual displays or mouse event relay. */
class DesktopTaskController(private val context: Context?, private val shell: (String) -> String) {
    private val windowBounds = mutableMapOf<Int, Rect>()
    private val launchedTasks = mutableSetOf<Int>()
    private fun service(): Any {
        val clazz = Class.forName("android.app.ActivityTaskManager")
        return Reflect.findMethod(clazz, "getService")?.invoke(null) ?: error("无 ActivityTaskManager")
    }

    private fun display(id: Int) {
        require(id > 0) { "只允许操作物理外屏" }
        val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: error("无 DisplayManager")
        val d = dm.getDisplay(id) ?: error("外屏已断开")
        check(Reflect.findMethod(d.javaClass, "getType")?.invoke(d) == 2) { "目标不是物理外屏" }
    }

    private fun rawTasks(id: Int): List<Any> {
        display(id)
        val svc = service()
        val method = Reflect.findMethod(svc.javaClass, "getTasks", Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            ?: error("本机未提供可靠的 getTasks 签名")
        val tasks = method.invoke(svc, 100, false, false, id) as? List<*> ?: error("任务列表不可读取")
        return tasks.filterNotNull().filter { Reflect.getField(it, "displayId").getOrThrow() == id }
    }

    private fun number(task: Any, field: String) = Reflect.getField(task, field).getOrThrow() as Int
    private fun component(task: Any): ComponentName? =
        Reflect.getField(task, "topActivity").getOrThrow() as? ComponentName
    private fun bounds(task: Any): Rect {
        val config = Reflect.getField(task, "configuration").getOrThrow()
        val window = Reflect.getField(config, "windowConfiguration").getOrThrow() ?: error("无窗口配置")
        return Rect(Reflect.findMethod(window.javaClass, "getBounds")?.invoke(window) as? Rect
            ?: error("无窗口边界"))
    }
    private fun mode(task: Any): Int {
        val config = Reflect.getField(task, "configuration").getOrThrow()
        val window = Reflect.getField(config, "windowConfiguration").getOrThrow()
        return Reflect.findMethod(window?.javaClass, "getWindowingMode")?.invoke(window) as? Int ?: -1
    }
    private fun task(id: Int, taskId: Int) = rawTasks(id).firstOrNull { number(it, "taskId") == taskId }
        ?: error("任务已关闭或不在目标外屏")

    fun list(id: Int): String = runCatching {
        val array = JSONArray()
        val current = rawTasks(id)
        current.forEach { t ->
            val comp = component(t) ?: return@forEach
            if (comp.packageName == "com.paddisplay.app") return@forEach
            val rect = bounds(t)
            array.put(JSONObject().put("id", number(t, "taskId")).put("package", comp.packageName)
                .put("component", comp.flattenToString()).put("mode", mode(t))
                .put("visible", Reflect.getField(t, "isVisible").getOrNull() as? Boolean ?: false)
                .put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom))
        }
        val active = current.firstOrNull()?.takeIf { component(it)?.packageName != "com.paddisplay.app" }
        JSONObject().put("ok", true).put("tasks", array).put("activeTaskId", active?.let { number(it, "taskId") } ?: -1)
            .put("desktopVisible", current.firstOrNull()?.let { component(it)?.className == "com.paddisplay.app.desktop.DesktopActivity" } == true).toString()
    }.getOrElse { JSONObject().put("ok", false).put("error", Reflect.describe(it)).toString() }

    private fun result(block: () -> String) = runCatching { "RESULT_OK=true\n${block()}" }
        .getOrElse { "RESULT_OK=false\n${Reflect.describe(it)}" }

    fun launch(id: Int, component: String, freeform: Boolean): String = result {
        display(id)
        val comp = ComponentName.unflattenFromString(component) ?: error("无效应用组件")
        require(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(comp.flattenToString()))
        val output = shell("/system/bin/am start --display $id ${if (freeform) "--windowingMode 5" else "--windowingMode 1"} -n ${comp.flattenToString()}")
        check(output.isNotBlank() && !Regex("Error|Exception|\\[exit=", RegexOption.IGNORE_CASE).containsMatchIn(output)) { output }
        var found: Any? = null
        for (attempt in 0 until 15) {
            SystemClock.sleep(100)
            found = rawTasks(id).firstOrNull()?.takeIf { component(it)?.packageName == comp.packageName }
            if (found != null) break
        }
        val t = found ?: error("启动未获外屏任务读回确认：$output")
        launchedTasks += number(t, "taskId")
        val actualMode = mode(t)
        if (freeform) {
            val converted = action(id, number(t, "taskId"), "window")
            check(converted.startsWith("RESULT_OK=true")) { "应用已在外屏打开，但自由窗口请求被系统拒绝：$converted" }
            return@result "外屏应用已打开，自由窗口模式已读回"
        }
        "外屏任务已读回：${comp.packageName}，windowingMode=$actualMode\n" +
            if (freeform && actualMode != 5) "系统未采用自由窗口；当前以实际窗口模式运行。" else ""
    }

    fun action(id: Int, taskId: Int, action: String): String = result {
        val original = task(id, taskId)
        check(component(original)?.packageName != "com.paddisplay.app") { "不能关闭桌面自身" }
        val svc = service()
        when (action) {
            "focus" -> {
                val caller = Class.forName("android.app.IApplicationThread")
                val method = Reflect.findMethod(svc.javaClass, "moveTaskToFront", caller, String::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Bundle::class.java)
                    ?: error("本机不支持原生任务切换")
                method.invoke(svc, null, "com.android.shell", taskId, 0, Bundle())
                SystemClock.sleep(150)
                check(number(rawTasks(id).firstOrNull() ?: error("无任务"), "taskId") == taskId) { "任务切换读回未确认" }
                "已切换到外屏任务 $taskId"
            }
            "close" -> {
                val method = Reflect.findMethod(svc.javaClass, "removeTask", Int::class.javaPrimitiveType)
                    ?: error("本机不支持关闭任务")
                check(method.invoke(svc, taskId) == true) { "系统拒绝关闭任务" }
                SystemClock.sleep(150)
                check(rawTasks(id).none { number(it, "taskId") == taskId }) { "任务关闭读回未确认" }
                windowBounds.remove(taskId)
                "已关闭外屏任务 $taskId"
            }
            "fullscreen", "window" -> {
                val targetMode = if (action == "fullscreen") 1 else 5
                if (targetMode == 1 && mode(original) == 5) windowBounds[taskId] = bounds(original)
                val token = Reflect.getField(original, "token").getOrThrow() ?: error("任务 token 不可读取")
                val tokenClass = Class.forName("android.window.WindowContainerToken")
                val transactionClass = Class.forName("android.window.WindowContainerTransaction")
                val transaction = transactionClass.getDeclaredConstructor().newInstance()
                Reflect.findMethod(transactionClass, "setWindowingMode", tokenClass, Int::class.javaPrimitiveType)
                    ?.invoke(transaction, token, targetMode) ?: error("系统未提供窗口模式事务")
                val space = CoordinateSpaceProbe(shell).injectionSpace(id) ?: error("外屏坐标空间不可读取")
                check(space.raw.startsWith("cur=")) { "没有当前坐标空间" }
                val desired = if (targetMode == 1) Rect() else windowBounds[taskId]?.takeIf {
                    it.left >= 0 && it.top >= 0 && it.right <= space.width && it.bottom <= space.height
                } ?: Rect(space.width / 6, space.height / 6, space.width * 5 / 6, space.height * 5 / 6)
                Reflect.findMethod(transactionClass, "setBounds", tokenClass, Rect::class.java)
                    ?.invoke(transaction, token, desired) ?: error("系统未提供窗口边界事务")
                val organizer = Reflect.findMethod(svc.javaClass, "getWindowOrganizerController")?.invoke(svc)
                    ?: error("无 WindowOrganizer")
                val apply = Reflect.findMethod(organizer.javaClass, "applyTransaction", transactionClass)
                    ?: error("系统未提供窗口组织事务")
                val transactionError = runCatching { apply.invoke(organizer, transaction) }.exceptionOrNull()
                fun adopted(): Boolean {
                    val actual = task(id, taskId)
                    return mode(actual) == targetMode && (targetMode == 1 || bounds(actual) == desired)
                }
                var verified = false
                repeat(15) {
                    if (!verified) { SystemClock.sleep(100); verified = adopted() }
                }
                // Re-resolve the task's launch root through the framework, without changing
                // display-wide policy or forcing a non-resizable app to become resizable.
                var retry = "未重试"
                if (!verified) {
                    retry = runCatching {
                        val options = ActivityOptions.makeBasic().setLaunchDisplayId(id).setLaunchBounds(desired)
                        Reflect.findMethod(options.javaClass, "setLaunchWindowingMode", Int::class.javaPrimitiveType)
                            ?.invoke(options, targetMode) ?: error("无窗口启动选项")
                        val restart = Reflect.findMethod(svc.javaClass, "startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java)
                            ?: error("无任务恢复接口")
                        restart.invoke(svc, taskId, options.toBundle())
                        repeat(15) { if (!verified) { SystemClock.sleep(100); verified = adopted() } }
                        "系统任务恢复请求已提交"
                    }.getOrElse { Reflect.describe(it) }
                }
                val actual = task(id, taskId)
                check(verified) {
                    "自由窗口/全屏未确认：task=$taskId，mode=${mode(actual)}，bounds=${bounds(actual)}，请求=$desired；$retry；" +
                        "事务=${transactionError?.let { Reflect.describe(it) } ?: "已提交"}；" +
                        "系统 freeform feature=${context?.packageManager?.hasSystemFeature("android.software.freeform_window_management")}；未修改厂商配置"
                }
                if (targetMode == 5) "自由窗口模式及边界已读回：task=$taskId，bounds=${bounds(actual)}" else "全屏模式已读回"
            }
            else -> error("未知任务操作")
        }
    }

    /** Move user tasks back rather than closing applications when the desktop session ends. */
    fun returnTasksToInternal(id: Int): String = result {
        display(id)
        val svc = service()
        val getRoots = Reflect.findMethod(svc.javaClass, "getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
            ?: error("系统未提供外屏根任务枚举")
        val move = Reflect.findMethod(svc.javaClass, "moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            ?: error("系统未提供任务回迁接口")
        val roots = (getRoots.invoke(svc, id) as? List<*>)?.filterNotNull() ?: error("外屏根任务不可读取")
        roots.filter { root ->
            val children = Reflect.getField(root, "childTaskIds").getOrNull() as? IntArray ?: intArrayOf()
            if (children.isEmpty()) number(root, "taskId") in launchedTasks else children.all { it in launchedTasks }
        }.forEach { root ->
            // Home / recents roots are owned by the system, never move them.
            val config = Reflect.getField(root, "configuration").getOrThrow()
            val wc = Reflect.getField(config, "windowConfiguration").getOrThrow()
            val type = Reflect.findMethod(wc?.javaClass, "getActivityType")?.invoke(wc) as? Int
            if (type == 1 && component(root) != null) move.invoke(svc, number(root, "taskId"), 0)
        }
        SystemClock.sleep(200)
        check(rawTasks(id).none { number(it, "taskId") in launchedTasks }) {
            "本次启动的应用仍停留在外屏；已保留系统状态，没有修改厂商调度"
        }
        launchedTasks.clear()
        "外屏应用已回迁内屏；等待系统默认复制策略"
    }

    fun ownSurface(id: Int, retire: Boolean): String = result {
        val own = rawTasks(id).filter { t -> component(t)?.let {
            it.packageName == "com.paddisplay.app" && it.className in setOf(
                "com.paddisplay.app.desktop.DesktopActivity", "com.paddisplay.app.desktop.EscapeNavigationActivity")
        } == true }
        val svc = service()
        if (retire) {
            val remove = Reflect.findMethod(svc.javaClass, "removeTask", Int::class.javaPrimitiveType) ?: error("无法移除自有桌面任务")
            own.forEach { check(remove.invoke(svc, number(it,"taskId")) == true) }
            "已移除自有外屏桌面任务"
        } else {
            val desktop = own.firstOrNull { component(it)?.className?.endsWith(".DesktopActivity") == true } ?: error("桌面任务尚未出现")
            val token = Reflect.getField(desktop,"token").getOrThrow() ?: error("无桌面 token")
            val tc = Class.forName("android.window.WindowContainerTransaction")
            val wc = Class.forName("android.window.WindowContainerToken")
            val tx = tc.getDeclaredConstructor().newInstance()
            Reflect.findMethod(tc,"setWindowingMode",wc,Int::class.javaPrimitiveType)?.invoke(tx,token,1) ?: error("无法设置自有桌面全屏")
            Reflect.findMethod(tc,"setBounds",wc,Rect::class.java)?.invoke(tx,token,Rect()) ?: error("无法清除自有桌面旧边界")
            val organizer = Reflect.findMethod(svc.javaClass,"getWindowOrganizerController")?.invoke(svc) ?: error("无 WindowOrganizer")
            val apply = Reflect.findMethod(organizer.javaClass,"applyTransaction",tc) ?: error("无窗口事务接口")
            apply.invoke(organizer,tx)
            var verified = false
            repeat(10) { if (!verified) { SystemClock.sleep(100); verified = mode(task(id,number(desktop,"taskId"))) == 1 } }
            check(verified) { "系统未确认自有桌面全屏；未修改显示器窗口策略" }
            "自有桌面已全屏读回，不影响应用窗口模式"
        }
    }

    fun resize(id: Int, taskId: Int, l: Int, t: Int, r: Int, b: Int): String = result {
        val original = task(id, taskId)
        check(mode(original) == 5) { "该应用不是自由窗口，系统不支持调整其窗口尺寸" }
        val space = CoordinateSpaceProbe(shell).injectionSpace(id) ?: error("无法读取外屏实际坐标空间")
        check(space.raw.startsWith("cur=")) { "没有当前坐标空间读回，拒绝调整窗口" }
        require(l >= 0 && t >= 0 && r <= space.width && b <= space.height && r - l >= 160 && b - t >= 120)
        val target = Rect(l, t, r, b)
        val svc = service()
        val method = Reflect.findMethod(svc.javaClass, "resizeTask", Int::class.javaPrimitiveType,
            Rect::class.java, Int::class.javaPrimitiveType) ?: error("本机没有 resizeTask 接口")
        method.invoke(svc, taskId, target, 0)
        SystemClock.sleep(150)
        val actual = bounds(task(id, taskId))
        check(actual == target) { "系统调整后边界与请求不一致：$actual" }
        "窗口边界已读回：$actual"
    }
}
