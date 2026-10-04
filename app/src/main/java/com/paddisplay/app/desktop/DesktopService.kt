package com.paddisplay.app.desktop

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.paddisplay.app.display.DisplaySnapshot
import com.paddisplay.app.shizuku.ShizukuManager
import com.paddisplay.app.system.SystemDisplayService
import com.paddisplay.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns a visible desktop session and only a small native taskbar overlay. */
class DesktopService : Service() {
    companion object {
        fun send(context: Context, action: String = "start", component: String? = null, taskId: Int = -1,
                 placement: String? = null) {
            val intent = Intent(context, DesktopService::class.java).putExtra("action", action)
                .putExtra("component", component).putExtra("task", taskId).putExtra("placement", placement)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val lifetime = Binder()
    private lateinit var system: SystemDisplayService
    private lateinit var store: DesktopStore
    private var target: DisplaySnapshot? = null
    private var preferredIdentity: String? = null
    private var registered = false
    private var stopping = false
    private var overlay: View? = null
    private var windowControls: View? = null
    private var navigation: View? = null
    private var decorations: NativeWindowDecorations? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var windowManager: WindowManager? = null
    private var overlayContext: Context? = null
    private var collapsed = false
    private var panelSignature = ""
    private var revealUntil = 0L
    private var edgeView: View? = null
    private var edgeManager: WindowManager? = null
    private val edgeHandler = Handler(Looper.getMainLooper())
    private val edgeReveal = Runnable { send(this, "escape") }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { scope.launch { delay(900); mutex.withLock {
            runCatching { reconnect() }.onFailure { message("外屏重连未完成：${it.message}") }
        } } }
        override fun onDisplayRemoved(displayId: Int) {
            if (target?.displayId == displayId) scope.launch { mutex.withLock {
                removeEdge(); removeOverlay()
                val restored = withContext(Dispatchers.IO) {
                    val mouse = runCatching { ShizukuManager.service?.releaseDesktopSession(lifetime) ?: "Shizuku 已断开，鼠标解绑未确认" }
                        .getOrElse { "鼠标恢复失败：${it.message}" }
                    val power = runCatching { system.restoreInternalDisplayWithRetry().toText() }
                        .getOrElse { "内屏恢复失败：${it.message}" }
                    "$mouse\n$power"
                }
                registered = false; target = null
                DesktopState.state.update { it.copy(displayId = -1, tasks = emptyList(), overlayReady = false,
                    message = "外屏已断开；等待原显示器重连\n$restored") }
                if (!store.autoReconnect) finishSession()
            } }
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == target?.displayId) scope.launch { mutex.withLock {
                runCatching { removeEdge(); removeOverlay(); refreshTasks() }.onFailure { message("外屏刷新失败：${it.message}") }
            } }
        }
    }
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { scope.launch { delay(300); mutex.withLock {
            if (target != null) runCatching { bindMouse() }.onFailure { message("鼠标重连验证失败：${it.message}") }
        } } }
        override fun onInputDeviceRemoved(deviceId: Int) {
            DesktopState.state.update { it.copy(message = "输入设备已断开；重连后将重新验证鼠标关联") }
        }
        override fun onInputDeviceChanged(deviceId: Int) = Unit // Association itself emits this event.
    }

    override fun onCreate() {
        super.onCreate()
        system = SystemDisplayService(this); store = DesktopStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("desktop", "外屏桌面", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DesktopService::class.java).putExtra("action", "stop"), PendingIntent.FLAG_IMMUTABLE)
        val rescue = PendingIntent.getService(this, 2, Intent(this, DesktopService::class.java).putExtra("action", "escape"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "desktop").setSmallIcon(com.paddisplay.app.R.mipmap.ic_launcher)
            .setContentTitle("PadDisplay 主机模式").setContentText("外屏桌面运行中 · 点击打开控制面板")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "唤出导航", rescue).build()).addAction(Notification.Action.Builder(null, "退出并恢复", stop).build()).build()
        startForeground(24, notification)
        ShizukuManager.init(this)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputListener, Handler(Looper.getMainLooper()))
        scope.launch {
            while (isActive) {
                delay(3000)
                mutex.withLock { if (target != null && !stopping) runCatching { refreshTasks() }
                    .onFailure { message("外屏任务刷新失败：${it.message}") } }
            }
        }
        scope.launch {
            ShizukuManager.connectEpoch.collect {
                if (it > 0 && target != null && !stopping) mutex.withLock {
                    runCatching { registered = false; bindMouse(); refreshTasks() }
                        .onFailure { message("Shizuku 重连未完成：${it.message}") }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "stop"
        scope.launch { mutex.withLock {
            try {
                when (action) {
                    "start" -> startSession()
                    "stop" -> finishSession()
                    "home" -> showDesktop()
                    "back" -> {
                        val d = requireTarget()
                        delay(150) // A dismissed escape dialog must release focus before targeting the app.
                        val output = withContext(Dispatchers.IO) {
                            ShizukuManager.service?.execCommand("/system/bin/input -d ${d.displayId} keyevent 4") ?: error("Shizuku 已断开")
                        }
                        if (Regex("Error|Exception|\\[exit=", RegexOption.IGNORE_CASE).containsMatchIn(output)) message("外屏返回未完成：$output")
                    }
                    "tasks" -> showDesktop(tasks = true)
                    "controls" -> showDesktop(controls = true)
                    "escape" -> {
                        val d = requireTarget()
                        revealUntil = android.os.SystemClock.uptimeMillis() + 15000
                        val expiration = revealUntil
                        panelSignature = ""
                        renderTaskbar() // Reveal overlays even if ColorOS rejects the dialog launch.
                        runCatching {
                            startActivity(Intent(this@DesktopService, EscapeNavigationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).toBundle())
                        }.onFailure { message("导航浮层已请求；焦点对话框启动失败：${it.message}") }
                        refreshTasks()
                        scope.launch {
                            delay(15000)
                            mutex.withLock { if (revealUntil == expiration) { revealUntil = 0L; panelSignature = ""; renderTaskbar() } }
                        }
                    }
                    "launchpad" -> showDesktop(true)
                    "retry" -> bindMouse()
                    "favorite" -> {
                        intent?.getStringExtra("component")?.let { store.toggleFavorite(it) }
                        DesktopState.state.update { it.copy(favorites = store.favorites()) }
                        panelSignature = ""; renderTaskbar()
                    }
                    "launch" -> {
                        val d = requireTarget()
                        val component = intent?.getStringExtra("component") ?: error("未选择应用")
                        val output = withContext(Dispatchers.IO) {
                            val svc = ShizukuManager.service ?: error("Shizuku 已断开")
                            val windowResult = svc.launchDesktopApp(d.displayId, component, store.freeform)
                            if (store.freeform && windowResult.startsWith("RESULT_OK=false")) {
                                val pkg = android.content.ComponentName.unflattenFromString(component)?.packageName ?: error("应用组件无效")
                                runCatching {
                                    DesktopState.parseTasks(svc.desktopTasks(d.displayId)).firstOrNull { it.packageName == pkg }
                                        ?.let { svc.desktopTaskAction(d.displayId,it.id,"fullscreen") }
                                }
                                val fallback = svc.launchAppOnDisplay(d.displayId,component,pkg)
                                "$windowResult\n自由窗口失败，已请求与工作台相同的全屏启动路径（不计作自由窗口成功）：\n$fallback"
                            } else windowResult
                        }
                        message(output); refreshTasks()
                    }
                    "focus", "close", "fullscreen", "window" -> {
                        val d = requireTarget()
                        val output = withContext(Dispatchers.IO) {
                            ShizukuManager.service?.desktopTaskAction(d.displayId, intent!!.getIntExtra("task", -1), action)
                                ?: error("Shizuku 已断开")
                        }
                        message(output); refreshTasks()
                    }
                    "place" -> place(intent!!.getIntExtra("task", -1), intent.getStringExtra("placement") ?: "center")
                    "bounds" -> {
                        val d = requireTarget()
                        val bounds = intent?.getIntArrayExtra("bounds") ?: error("缺少窗口边界")
                        require(bounds.size == 4)
                        val result = withContext(Dispatchers.IO) {
                            ShizukuManager.service?.resizeDesktopTask(d.displayId, intent!!.getIntExtra("task", -1), bounds[0], bounds[1], bounds[2], bounds[3]) ?: error("Shizuku 已断开")
                        }
                        message(result); refreshTasks()
                    }
                    "refresh" -> {
                        val apps = system.listLaunchableApps()
                        DesktopState.state.update { it.copy(apps = apps) }
                        panelSignature = ""; refreshTasks()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                message("操作未完成：${t.message}")
                if (action == "start" && target == null) finishSession()
            }
        } }
        return START_NOT_STICKY
    }

    private fun message(text: String) { DesktopState.state.update { it.copy(message = text) } }
    private fun requireTarget(): DisplaySnapshot {
        val previous = target ?: error("外屏未连接")
        val current = system.enumerateDisplays().firstOrNull {
            it.typeCode == 2 && it.displayId == previous.displayId && store.displayKey(it) == preferredIdentity
        } ?: error("原外屏已断开或重新枚举")
        return current
    }

    private suspend fun startSession() {
        system.enumerateDisplays()
        val d = system.primaryExternal()?.takeIf { it.typeCode == 2 && it.displayId > 0 } ?: error("未连接物理外屏")
        target = d; preferredIdentity = store.displayKey(d)
        DesktopState.state.update { it.copy(running = true, displayId = d.displayId, favorites = store.favorites()) }
        val apps = system.listLaunchableApps()
        DesktopState.state.update { it.copy(apps = apps) }
        restoreSavedScale(d)
        withContext(Dispatchers.IO) {
            // Only our own overlay app-op. The subsequent Settings check remains authoritative.
            runCatching { ShizukuManager.service?.execCommand("cmd appops set --user current com.paddisplay.app SYSTEM_ALERT_WINDOW allow") }
        }
        bindMouse()
        showDesktop(); refreshTasks()
    }

    private suspend fun reconnect() {
        if (stopping || target != null || !store.autoReconnect || preferredIdentity == null) return
        val d = system.enumerateDisplays().firstOrNull { it.typeCode == 2 && store.displayKey(it) == preferredIdentity } ?: return
        target = d
        DesktopState.state.update { it.copy(displayId = d.displayId, running = true) }
        restoreSavedScale(d); bindMouse()
        showDesktop(); refreshTasks()
    }

    private suspend fun restoreSavedScale(d: DisplaySnapshot) {
        val percent = store.scale(d) ?: return
        runCatching {
            val original = system.displayDensity.read(d.displayId)
            val prefs = getSharedPreferences("display_density_original", MODE_PRIVATE)
            val key = store.displayKey(d)
            if (!prefs.contains(key)) check(prefs.edit().putInt(key, original.override ?: -1).commit())
            requireTarget()
            system.displayDensity.write(d.displayId, (original.physical * percent / 100.0).toInt())
        }.onFailure { message("记忆缩放应用失败：${it.message}") }
    }

    private suspend fun bindMouse() {
        if (target == null || stopping) return
        val d = requireTarget()
        val output = withContext(Dispatchers.IO) {
            val svc = ShizukuManager.service ?: error("Shizuku 未连接")
            if (!registered) {
                val prefs = getSharedPreferences("display_density_original", MODE_PRIVATE)
                val original = if (prefs.contains(store.displayKey(d))) prefs.getInt(store.displayKey(d), -1)
                    else runCatching { system.displayDensity.read(d.displayId).override ?: -1 }.getOrDefault(-2)
                val registration = svc.registerDesktopSession(lifetime, d.displayId, original)
                check(registration.contains("RESULT_OK=true")) { registration }
                registered = true
            }
            svc.bindInputToDisplay(d.displayId)
        }
        message(output)
    }

    private suspend fun showDesktop(launchpad: Boolean = false, tasks: Boolean = false, controls: Boolean = false) {
        if (stopping) return
        val d = requireTarget()
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId)
        startActivity(Intent(this, DesktopActivity::class.java).putExtra("launchpad", launchpad).putExtra("tasks", tasks).putExtra("controls", controls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        delay(180)
        val result = withContext(Dispatchers.IO) { ShizukuManager.service?.ensureDesktopSurface(d.displayId) ?: "Shizuku 已断开" }
        if (!result.startsWith("RESULT_OK=true")) message(result)
    }

    private suspend fun refreshTasks() {
        if (target == null) return
        val d = requireTarget()
        runCatching {
            val output = withContext(Dispatchers.IO) { ShizukuManager.service?.desktopTasks(d.displayId) ?: error("Shizuku 已断开") }
            Triple(DesktopState.parseTasks(output), org.json.JSONObject(output).optInt("activeTaskId", -1), org.json.JSONObject(output).optBoolean("desktopVisible"))
        }.fold(
            { (tasks, active, desktopVisible) -> DesktopState.state.update { it.copy(tasks = tasks, activeTaskId = active, desktopVisible = desktopVisible, taskError = null, favorites = store.favorites()) } },
            { error -> DesktopState.state.update { it.copy(tasks = emptyList(), activeTaskId = -1, taskError = error.message) } },
        )
        val space = withContext(Dispatchers.IO) {
            runCatching { com.paddisplay.app.system.CoordinateSpaceProbe { ShizukuManager.service?.execCommand(it) ?: "" }.injectionSpace(d.displayId) }.getOrNull()
        }
        viewportWidth = if (space?.raw?.startsWith("cur=") == true) space.width else 0
        viewportHeight = if (space?.raw?.startsWith("cur=") == true) space.height else 0
        renderTaskbar()
    }

    private suspend fun place(taskId: Int, placement: String) {
        val d = requireTarget()
        val output = withContext(Dispatchers.IO) {
            val probe = com.paddisplay.app.system.CoordinateSpaceProbe { command ->
                ShizukuManager.service?.execCommand(command) ?: error("Shizuku 未连接")
            }
            val space = probe.injectionSpace(d.displayId) ?: error("外屏坐标空间不可读")
            check(space.raw.startsWith("cur=")) { "当前外屏坐标空间未获确认" }
            val w = space.width; val h = space.height
            val task = DesktopState.state.value.tasks.firstOrNull { it.id == taskId } ?: error("任务已关闭")
            val dpi = system.displayDensity.read(d.displayId).effective
            val rect = WindowPlacement.bounds(w, h, (96 * dpi / 160.0).toInt(), placement,
                intArrayOf(task.left, task.top, task.right, task.bottom))
            ShizukuManager.service?.resizeDesktopTask(d.displayId, taskId, rect[0], rect[1], rect[2], rect[3]) ?: error("Shizuku 已断开")
        }
        message(output); refreshTasks()
    }

    private fun renderTaskbar() {
        val d = target ?: return
        val state = DesktopState.state.value
        val revealed = android.os.SystemClock.uptimeMillis() < revealUntil
        updateEdge(!state.desktopVisible && !revealed)
        val foregroundTask = state.tasks.firstOrNull { it.id == state.activeTaskId } ?: state.tasks.firstOrNull()
        val fullscreen = foregroundTask != null && foregroundTask.mode == 1
        val immersive = store.hideForGames && state.tasks.firstOrNull()?.packageName?.let {
            it.contains("limelight", true) || it.contains("moonlight", true)
        } == true
        if (!Settings.canDrawOverlays(this) || ((immersive || fullscreen) && !revealed) || (state.desktopVisible && !revealed)) {
            removeOverlay()
            DesktopState.state.update { it.copy(overlayReady = false) }
            return
        }
        if (decorations?.dragging == true) return
        val dark = com.paddisplay.app.ui.ThemeSettings.dark(this)
        val signature = "${d.displayId}|${state.tasks}|${state.activeTaskId}|${state.favorites}|$collapsed|$dark|$viewportWidth|$viewportHeight|$revealed"
        if (overlay != null && signature == panelSignature) return
        removeOverlay()
        try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(d.displayId) ?: return
            val ctx = createDisplayContext(display).let {
                if (android.os.Build.VERSION.SDK_INT >= 30) it.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else it
            }
            overlayContext = ctx
            val wm = ctx.getSystemService(WindowManager::class.java)
            val scale = ctx.resources.displayMetrics.density
            fun dp(value: Int) = (value * scale).toInt()
            val foreground = if (dark) Color.rgb(232, 237, 245) else Color.rgb(32, 43, 61)
            val background = if (dark) Color.rgb(30, 39, 54) else Color.rgb(245, 247, 252)
            fun rounded() = android.graphics.drawable.GradientDrawable().apply { setColor(background); cornerRadius = dp(22).toFloat(); setStroke(dp(1), if (dark) Color.rgb(68, 82, 105) else Color.rgb(212, 222, 239)) }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                this.background = rounded(); setPadding(dp(10), dp(6), dp(10), dp(6)); elevation = dp(12).toFloat()
            }
            fun button(label: String, action: () -> Unit) {
                row.addView(TextView(ctx).apply {
                    text = label; textSize = 14f; setTextColor(foreground); gravity = Gravity.CENTER
                    setPadding(18, 10, 18, 10); isClickable = true; setOnClickListener { action() }
                })
            }
            button("▦") { send(this, "launchpad") }
            fun icon(pkg: String, label: String, running: Boolean, action: () -> Unit) {
                row.addView(android.widget.ImageView(ctx).apply {
                    setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
                    contentDescription = label + if (running) " · 已打开" else ""
                    setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true; setOnClickListener { action() }
                    if (running) this.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (dark) Color.rgb(58, 75, 102) else Color.rgb(218, 229, 249)); cornerRadius = dp(14).toFloat()
                    }
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
            }
            if (!collapsed) {
                DockApps.select(state.apps, state.favorites, 8).forEach { app ->
                    val task = state.tasks.firstOrNull { it.packageName == app.packageName }
                    icon(app.packageName, app.label, task != null) {
                        if (task == null) send(this, "launch", app.component) else send(this, "focus", taskId = task.id)
                    }
                }
                state.tasks.filter { it.packageName !in DockApps.select(state.apps, state.favorites, 8).map { app -> app.packageName } }.take(12).forEach { task ->
                    val label = state.apps.firstOrNull { it.packageName == task.packageName }?.label ?: task.packageName.substringAfterLast('.')
                    icon(task.packageName, label, true) { send(this, "focus", taskId = task.id) }
                }
                button("桌面") { send(this, "home") }
                button("☷") { send(this, "controls") }
                button("收起") { collapsed = true; panelSignature = ""; renderTaskbar() }
            } else button("展开") { collapsed = false; panelSignature = ""; renderTaskbar() }
            val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            val params = WindowManager.LayoutParams(
                if (viewportWidth > 0) (viewportWidth - dp(360)).coerceIn(dp(120), dp(840)) else dp(360),
                dp(72), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(12) }
            wm.addView(scroll, params)
            windowManager = wm; overlay = scroll; panelSignature = signature
            val nav = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; this.background = rounded(); setPadding(dp(6), dp(4), dp(6), dp(4))
            }
            listOf("‹" to "back", "⌂" to "home", "▣" to "tasks").forEach { (label, action) ->
                nav.addView(TextView(ctx).apply {
                    text = label; textSize = 22f; setTextColor(foreground); gravity = Gravity.CENTER
                    contentDescription = when (action) { "back" -> "返回"; "home" -> "Home"; else -> "多任务" }
                    isClickable = true; setOnClickListener { send(this@DesktopService, action) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            val navParams = WindowManager.LayoutParams(dp(156), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
                .apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(16); y = dp(16) }
            wm.addView(nav, navParams); navigation = nav
            if (viewportWidth >= 320 && viewportHeight >= 240) {
                decorations = NativeWindowDecorations(ctx, wm,
                    { action, task -> send(this, action, taskId = task) },
                    { task, bounds -> androidx.core.content.ContextCompat.startForegroundService(this,
                        Intent(this, DesktopService::class.java).putExtra("action", "bounds").putExtra("task", task).putExtra("bounds", bounds)) })
                    .also { it.show(state, viewportWidth, viewportHeight, dark) }
            }
            foregroundTask?.takeIf { it.mode != 5 }?.let { task ->
                val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; this.background = rounded(); setPadding(dp(8), dp(6), dp(8), dp(6)) }
                fun control(label: String, description: String, tint: Int = foreground, action: () -> Unit) {
                    controls.addView(TextView(ctx).apply { text = label; contentDescription = description; textSize = 16f; setTextColor(tint); setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true; setOnClickListener { action() } })
                }
                control("●", "关闭窗口", Color.rgb(225, 86, 83)) { send(this, "close", taskId = task.id) }
                control("●", "收起应用并返回桌面", Color.rgb(208, 148, 34)) { send(this, "home") }
                control("●", if (task.mode == 5) "全屏" else "恢复自由窗口", Color.rgb(47, 165, 101)) { send(this, if (task.mode == 5) "fullscreen" else "window", taskId = task.id) }
                val label = state.apps.firstOrNull { it.packageName == task.packageName }?.label ?: task.packageName.substringAfterLast('.')
                controls.addView(TextView(ctx).apply { text = label.take(20); setTextColor(foreground); setPadding(dp(10),0,dp(10),0) })
                if (task.mode == 5) {
                    control("−", "缩小窗口") { send(this, "place", taskId = task.id, placement = "smaller") }
                    control("+", "放大窗口") { send(this, "place", taskId = task.id, placement = "larger") }
                }
                val barParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(52), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
                    .apply { gravity = Gravity.TOP or Gravity.END; x = dp(20); y = dp(64) }
                wm.addView(controls, barParams); windowControls = controls
            }
            DesktopState.state.update { it.copy(overlayReady = true) }
        } catch (t: Throwable) { message("任务栏显示失败：${t.message}") }
    }

    /** Small bottom-only input target. It never requests focus or pointer capture. */
    private fun updateEdge(show: Boolean) {
        if (!show || !Settings.canDrawOverlays(this)) { removeEdge(); return }
        if (edgeView != null) return
        val d = target ?: return
        try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(d.displayId) ?: return
            val ctx = createDisplayContext(display).let {
                if (android.os.Build.VERSION.SDK_INT >= 30) it.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else it
            }
            val density = ctx.resources.displayMetrics.density
            fun dp(n: Int) = (n * density).toInt().coerceAtLeast(1)
            val wm = ctx.getSystemService(WindowManager::class.java)
            val view = android.widget.FrameLayout(ctx).apply {
                contentDescription = "全屏导航：鼠标在底边停留两秒，或点击底边"
                setBackgroundColor(Color.argb(12, 128, 128, 128))
                addView(View(ctx).apply { setBackgroundColor(Color.argb(150, 180, 190, 205)) },
                    android.widget.FrameLayout.LayoutParams(dp(80), dp(3), Gravity.CENTER))
                setOnHoverListener { _, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_HOVER_ENTER -> {
                            edgeHandler.removeCallbacks(edgeReveal)
                            edgeHandler.postDelayed(edgeReveal, 2000)
                        }
                        android.view.MotionEvent.ACTION_HOVER_EXIT -> edgeHandler.removeCallbacks(edgeReveal)
                    }
                    true
                }
                setOnClickListener { edgeHandler.removeCallbacks(edgeReveal); send(this@DesktopService, "escape") }
            }
            val width = ctx.resources.displayMetrics.widthPixels
            val params = WindowManager.LayoutParams((width - dp(48)).coerceAtLeast(dp(80)), dp(8),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
            wm.addView(view, params)
            edgeManager = wm; edgeView = view
        } catch (t: Throwable) { message("底边导航入口显示失败：${t.message}") }
    }

    private fun removeEdge() {
        edgeHandler.removeCallbacks(edgeReveal)
        edgeView?.let { runCatching { edgeManager?.removeViewImmediate(it) } }
        edgeView = null; edgeManager = null
    }

    private fun removeOverlay() {
        decorations?.clear(); decorations = null
        overlay?.let { runCatching { windowManager?.removeViewImmediate(it) } }
        windowControls?.let { runCatching { windowManager?.removeViewImmediate(it) } }
        navigation?.let { runCatching { windowManager?.removeViewImmediate(it) } }
        navigation = null
        windowControls = null
        overlay = null; windowManager = null; overlayContext = null
        DesktopState.state.update { it.copy(overlayReady = false) }
    }

    private suspend fun cleanup(): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        target?.let { d ->
            lines += runCatching {
                requireTarget()
                val retired = ShizukuManager.service?.retireDesktopSurface(d.displayId) ?: "Shizuku 已断开，自有桌面清理未确认"
                val restored = ShizukuManager.service?.restoreDesktopDisplay(d.displayId) ?: "Shizuku 已断开，系统复制策略恢复未确认"
                "$retired\n$restored"
            }.getOrElse { "外屏任务回迁失败：${it.message}" }
        }
        lines += runCatching { ShizukuManager.service?.releaseDesktopSession(lifetime) ?: "Shizuku 已断开，鼠标解绑未确认" }
            .getOrElse { "鼠标恢复失败：${it.message}" }
        registered = false
        val d = target
        if (d != null) runCatching {
            requireTarget()
            val prefs = getSharedPreferences("display_density_original", MODE_PRIVATE)
            val key = store.displayKey(d)
            if (prefs.contains(key)) {
                system.displayDensity.write(d.displayId, prefs.getInt(key, -1).takeIf { it >= 0 })
                check(prefs.edit().remove(key).commit())
                lines += "原 DPI 已读回恢复"
            }
        }.onFailure { lines += "缩放恢复失败：${it.message}" }
        lines += system.restoreInternalDisplayWithRetry().toText()
        lines.joinToString("\n")
    }

    private suspend fun finishSession() {
        stopping = true; revealUntil = 0L; removeEdge(); removeOverlay()
        DesktopState.state.update { it.copy(running = false) }
        delay(250) // Let the external desktop finish before releasing its native display content.
        val result = cleanup()
        target = null
        DesktopState.state.value = DesktopSnapshot(message = result)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        removeEdge(); removeOverlay()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputListener)
        if (!stopping) CoroutineScope(Dispatchers.IO).launch {
            val result = cleanup(); DesktopState.state.value = DesktopSnapshot(message = result)
        }
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
