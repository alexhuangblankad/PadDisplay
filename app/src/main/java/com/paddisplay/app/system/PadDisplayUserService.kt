package com.paddisplay.app.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import androidx.annotation.Keep
import com.paddisplay.app.BuildConfig
import com.paddisplay.app.IPadDisplayService
import com.paddisplay.app.display.DisplayModeSelector
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService 实现 —— 本项目的权限中枢（任务书第 10 节）。
 *
 * ## 运行位置与身份
 * 这个类不是运行在 App 进程里的。Shizuku 会用 `app_process` 把它拉起到一个
 * **uid = 2000 (shell)** 的独立进程（进程名 `:<suffix>`）中，
 * 因此它可以：
 *   - 通过 `ServiceManager.getService()` 拿到隐藏系统服务 Binder；
 *   - 调用 `IDisplayManager` / `IWindowManager` 这些需要 signature 级权限的接口
 *     （shell 是 platform 签名的 privileged app，持有
 *      `MODIFY_USER_PREFERRED_DISPLAY_MODE`、`MANAGE_DISPLAYS`）；
 *   - 反射调用 `android.view.SurfaceControl.setDisplayPowerMode()`；
 *   - 执行 `dumpsys` / `wm` 等 shell 命令。
 *
 * ## 为什么所有方法都返回 String
 * ColorOS 上失败原因可能是 SecurityException / NoSuchMethod / Binder 拒绝，
 * 这些必须原样带回 UI 的诊断页，否则没法排查。所以异常一律转成文本，
 * 而不是抛回 App 侧变成一个没信息的 RemoteException。
 *
 * ## 无 Root
 * 全程不调用 `su`、不写 /system、不申请 root。权限来源只有 Shizuku 的 shell 身份。
 */
class PadDisplayUserService(private val injectedContext: Context?) : IPadDisplayService.Stub() {

    @Suppress("unused")
    constructor() : this(null)

    private val powerController: DisplayPowerController by lazy {
        DisplayPowerController(
            displayManagerBinder = displayService(),
            contextProvider = { injectedContext },
        )
    }

    private val resolutionController: DisplayResolutionController by lazy {
        DisplayResolutionController(
            displayManagerBinder = displayService(),
            windowManagerBinder = windowService(),
            shellRunner = ::execCommand,
        )
    }

    init {
        ServiceContextHolder.context = injectedContext
    }

    private val desktopTasksController by lazy { DesktopTaskController(injectedContext, ::execRaw) }
    private var desktopLifetime: IBinder? = null
    private var desktopDeath: IBinder.DeathRecipient? = null
    private var desktopDisplayId = -1
    private var desktopDisplayUniqueId: String? = null
    private var desktopOriginalDensity = -1
    private var desktopOriginalWindowMode: Int? = null

    /** Real panel brightness, independent of the external monitor's hardware backlight. */
    override fun internalBrightness(value: Float): String = runCatching {
        require(value == -1f || value.isFinite() && value in 0f..1f)
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: error("无 DisplayManager")
        val internal = dm.displays.firstOrNull { Reflect.findMethod(it.javaClass, "getType")?.invoke(it) == 1 } ?: error("无内屏")
        val get = Reflect.findMethod(dm.javaClass, "getBrightness", Int::class.javaPrimitiveType) ?: error("系统不支持亮度读取")
        if (value >= 0f) {
            val set = Reflect.findMethod(dm.javaClass, "setBrightness", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType) ?: error("系统不支持亮度控制")
            set.invoke(dm, internal.displayId, value)
        }
        val actual = get.invoke(dm, internal.displayId) as? Float ?: error("亮度读回失败")
        check(actual.isFinite() && actual in 0f..1f) { "亮度不可用" }
        if (value >= 0f) check(kotlin.math.abs(actual-value) < .04f) { "系统亮度读回与请求不一致（自动亮度或 ROM 限制）" }
        org.json.JSONObject().put("ok",true).put("value",actual.toDouble()).toString()
    }.getOrElse { org.json.JSONObject().put("ok",false).put("error",Reflect.describe(it)).toString() }

    private fun restoreDesktopAfterDeath(): String = runCatching {
            val messages = mutableListOf<String>()
            var ok = true
            if (desktopOriginalWindowMode != null && desktopDisplayId > 0) {
                val restored = restoreDesktopDisplay(desktopDisplayId)
                messages += restored
                if (!restored.startsWith("RESULT_OK=true")) ok = false
            }
            val mouse = returnMouseToInternal()
            if (!mouse.contains("RESULT_OK=true")) ok = false
            messages += mouse
            val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val d = dm?.getDisplay(desktopDisplayId)
            if (d != null && desktopOriginalDensity != -2 && desktopDisplayUniqueId != null && inputController.displayIdentity(d).first == desktopDisplayUniqueId) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        DisplayDensityController { execRaw(it) }.write(desktopDisplayId, desktopOriginalDensity.takeIf { it >= 0 })
                    }
                    messages += "原外屏 DPI 已读回恢复"
                }.onFailure { ok = false; messages += "DPI 恢复失败：${Reflect.describe(it)}" }
            } else if (desktopDisplayId > 0) {
                messages += "原外屏不存在、身份变化或 DPI 快照不可用，未写入其它显示器"
            }
            "RESULT_OK=$ok\n" + messages.joinToString("\n")
        }.getOrElse { "RESULT_OK=false\n${Reflect.describe(it)}" }

    override fun desktopTasks(displayId: Int) = desktopTasksController.list(displayId)
    override fun launchDesktopApp(displayId: Int, component: String, freeform: Boolean) =
        desktopTasksController.launch(displayId, component, freeform)
    override fun desktopTaskAction(displayId: Int, taskId: Int, action: String) =
        desktopTasksController.action(displayId, taskId, action)
    override fun resizeDesktopTask(displayId: Int, taskId: Int, left: Int, top: Int, right: Int, bottom: Int) =
        desktopTasksController.resize(displayId, taskId, left, top, right, bottom)

    @Synchronized
    override fun prepareDesktopDisplay(displayId: Int): String = runCatching {
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: error("无 DisplayManager")
        val d = dm.getDisplay(displayId) ?: error("外屏已断开")
        require(displayId > 0 && Reflect.findMethod(d.javaClass, "getType")?.invoke(d) == 2)
        if (desktopOriginalWindowMode == null) desktopOriginalWindowMode = mirrorController.getWindowingMode(displayId)
        val report = mirrorController.setWindowingMode(displayId, 5)
        "RESULT_OK=${report.ok}\n${report.toText()}"
    }.getOrElse { "RESULT_OK=false\n${Reflect.describe(it)}" }

    @Synchronized
    override fun restoreDesktopDisplay(displayId: Int): String = runCatching {
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: error("无 DisplayManager")
        val d = dm.getDisplay(displayId) ?: error("外屏已断开")
        require(displayId > 0 && Reflect.findMethod(d.javaClass, "getType")?.invoke(d) == 2)
        check(displayId == desktopDisplayId && inputController.displayIdentity(d).first == desktopDisplayUniqueId) { "原外屏身份不匹配，未修改其它屏幕" }
        val tasks = desktopTasksController.returnTasksToInternal(displayId)
        val mode = mirrorController.setWindowingMode(displayId, desktopOriginalWindowMode ?: 0)
        if (mode.ok) desktopOriginalWindowMode = null
        "RESULT_OK=${tasks.startsWith("RESULT_OK=true") && mode.ok}\n$tasks\n${mode.toText()}\n已恢复系统显示策略，复制画面以显示器实际输出为准。"
    }.getOrElse { "RESULT_OK=false\n${Reflect.describe(it)}" }

    @Synchronized
    override fun registerDesktopSession(token: IBinder, displayId: Int, originalDensity: Int): String = runCatching {
        require(displayId > 0 && (originalDensity in -2..-1 || originalDensity in 72..1280))
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: error("无 DisplayManager")
        val d = dm.getDisplay(displayId) ?: error("外屏已断开")
        check(Reflect.findMethod(d.javaClass, "getType")?.invoke(d) == 2) { "会话目标不是物理外屏" }
        desktopDeath?.let { desktopLifetime?.unlinkToDeath(it, 0) }
        desktopLifetime = null
        val death = IBinder.DeathRecipient {
            synchronized(this) {
                if (desktopLifetime == token) {
                    desktopLifetime = null
                    android.util.Log.i("PadDisplay/Desktop", "进程退出恢复：${restoreDesktopAfterDeath()}")
                    desktopDisplayId = -1
                }
            }
        }
        token.linkToDeath(death, 0)
        desktopDeath = death
        desktopDisplayId = displayId
        desktopDisplayUniqueId = inputController.displayIdentity(d).first
        desktopOriginalDensity = originalDensity
        desktopLifetime = token
        "RESULT_OK=true\n桌面会话已注册" + if (originalDensity == -2) "；DPI 快照不可读取" else ""
    }.getOrElse { "RESULT_OK=false\n${Reflect.describe(it)}" }

    @Synchronized
    override fun releaseDesktopSession(token: IBinder): String {
        if (desktopLifetime != null && desktopLifetime != token) return "RESULT_OK=false\n会话身份不匹配"
        desktopDeath?.let { desktopLifetime?.unlinkToDeath(it, 0) }
        desktopLifetime = null
        val result = restoreDesktopAfterDeath()
        desktopDisplayId = -1
        return result
    }

    // ------------------------------------------------------------------
    // 系统服务 / 命令
    // ------------------------------------------------------------------

    @Synchronized
    override fun returnMouseToInternal(): String = runCatching {
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: error("无 DisplayManager")
        val internal = dm.getDisplay(resolveInternalDisplayIdViaContext()) ?: error("内屏不可读取")
        check(Reflect.call(internal, "getType").getOrNull() == 1) { "目标不是内屏" }
        // Bring the destination panel back before routing its physical pointer.
        val power = powerController.setPower(internal.displayId, DisplayPowerController.POWER_MODE_NORMAL)
        android.os.SystemClock.sleep(200)
        val cleared = inputController.clearAllAssociations()
        // Unassociated mice follow WMS policy, which may still select the external desktop.
        val bound = inputController.bindAllExternalInputToDisplay(internal)
        val verified = power.any { it.ok } && bound.lastOrNull { it.channel == "读回验证" }?.ok == true
        "RESULT_OK=$verified\n鼠标返回内屏 displayId=${internal.displayId}\n" +
            power.joinToString("\n") { it.toText() } + "\n" + (cleared + bound).joinToString("\n") { it.toText() }
    }.getOrElse { "RESULT_OK=false\n鼠标回内屏失败：${Reflect.describe(it)}" }

    /**
     * 取系统服务 Binder。
     * 用反射调 `android.os.ServiceManager.getService(name)`，
     * 避免依赖 Shizuku 的 SystemServiceHelper（它在 UserService 进程里不一定可用）。
     */
    private fun systemService(name: String): IBinder? {
        Reflect.classForName("android.os.ServiceManager")?.let { clazz ->
            Reflect.findMethod(clazz, "getService", String::class.java)?.let { m ->
                runCatching { return m.invoke(null, name) as? IBinder }
            }
        }
        // 退路：用 Context（Shizuku 注入的）拿
        return ServiceContextHolder.getSystemServiceBinder(name)
    }

    private fun displayService(): IBinder? = systemService("display")
    private fun windowService(): IBinder? = systemService("window")

    override fun execCommand(command: String): String = execRaw(command)

    /**
     * 执行 shell 命令。
     *
     * ⚠️ 关键修复：`wm` / `dumpsys` 是 `/system/bin` 下的可执行文件，
     * 但 Shizuku UserService 进程的 PATH **不一定包含** `/system/bin`。
     * 早先直接用 `sh -c "wm size -d N"`，命令可能静默失败（exit 127 / 无输出），
     * 于是 `readDisplaySize` 拿到空字符串，分辨率相关的快照与回滚全部失效。
     *
     * 现在显式把 PATH 补全，并**返回退出码与非零退出时的提示**，
     * 不再让失败伪装成"空输出"。
     */
    private fun execRaw(command: String): String {
        return try {
            val pb = ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
            // 显式补全 PATH，覆盖 UserService 进程 PATH 缺失的情况
            val env = pb.environment()
            val path = env["PATH"] ?: ""
            val extra = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
            if (!path.contains("/system/bin")) {
                env["PATH"] = if (path.isEmpty()) extra else "$extra:$path"
            }
            val process = pb.start()

            val sb = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
            }
            val code = process.waitFor()
            var out = sb.toString()
            if (code != 0 && out.isBlank()) {
                out = "[exit=$code, 无输出] 命令可能不存在或没有权限: $command"
            } else if (code != 0) {
                out = "$out\n[exit=$code]"
            }
            if (out.length > 200_000) out.substring(0, 200_000) + "\n...(输出已截断)" else out
        } catch (t: Throwable) {
            "execCommand 失败: ${Reflect.describe(t)}"
        }
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    override fun collectSystemInfo(): String = clean(buildString {
        appendLine("=== UserService 进程身份 ===")
        appendLine("uid=${android.os.Process.myUid()}  pid=${android.os.Process.myPid()}")
        appendLine("(uid=2000 表示处在 Shizuku 的 shell 权限进程中)")
        appendLine()
        appendLine("=== 设备 ===")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Android: ${Build.VERSION.RELEASE}")
        appendLine("SDK: ${Build.VERSION.SDK_INT}")
        appendLine("Build ID: ${Build.DISPLAY}")
        appendLine("ColorOS/ROM: ${runCatching { Build.VERSION.INCREMENTAL }.getOrNull()}")
        appendLine()
        appendLine("=== 系统服务 Binder ===")
        appendLine("display: ${if (displayService() != null) "可用" else "不可用"}")
        appendLine("window: ${if (windowService() != null) "可用" else "不可用"}")
        appendLine("package: ${if (systemService("package") != null) "可用" else "不可用"}")
        appendLine()
        appendLine("=== 关屏通道 ===")
        appendLine(powerController.describeChannels())
        appendLine("=== 分辨率通道 ===")
        appendLine(resolutionController.describeChannels())
    })

    /**
     * 清掉跨 Binder 传输后可能混进来的 BOM / 零宽字符。
     *
     * 背景：`buildString` 的产物首行可能带 `\uFEFF`，
     * 客户端用 `line.startsWith("uid=")` 就会匹配失败（曾经导致 uid 显示成 -1）。
     * 与其在解析侧四处打补丁，不如在产出侧就清掉。
     */
    private fun clean(s: String): String =
        s.replace("\uFEFF", "").replace("\u200B", "").replace("\u0000", "")

    override fun probeCapabilities(): String = clean(buildString {
        appendLine("PadDisplay v${BuildConfig.VERSION_NAME} 能力探测")
        appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine("[1] 身份")
        appendLine("  uid=${android.os.Process.myUid()} (期望 2000/shell)")
        appendLine()
        appendLine("[2] SurfaceControl 关屏通道")
        appendLine(powerController.describeChannels())
        appendLine("[3] per-display 分辨率通道")
        appendLine(resolutionController.describeChannels())
        appendLine()
        appendLine("[4] 关键命令可用性")
        appendLine("  wm: ${execCommand("which wm").trim().ifEmpty { "(未找到)" }}")
        appendLine("  dumpsys: ${execCommand("which dumpsys").trim().ifEmpty { "(未找到)" }}")
        appendLine()
        appendLine("[5] AIDL 调用日志（最近 40 条）")
        AidlCodec.callLog.takeLast(40).forEach { appendLine("  $it") }
    })

    override fun dumpDisplay(): String = execCommand("dumpsys display")

    override fun listPhysicalDisplays(): String = clean(buildString {
        appendLine("=== SurfaceControl / DisplayControl 物理屏 ===")
        val ids = PhysicalDisplayAccess.physicalDisplayIds()
        appendLine("physicalDisplayIds() = ${ids?.joinToString() ?: "(null)"}")
        ids?.forEach { id ->
            val token = PhysicalDisplayAccess.physicalDisplayToken(id)
            appendLine(
                "  physicalId=$id port=${(id and 0xFFL).toInt()} " +
                    "token=${if (token != null) "可用" else "不可用"}",
            )
        }
        appendLine(
            "internalDisplayToken() = " +
                (if (PhysicalDisplayAccess.internalDisplayToken() != null) "可用" else "不可用"),
        )
        appendLine("生效渠道: ${PhysicalDisplayAccess.activeChannel}")
        appendLine()
        appendLine("=== DisplayManager 逻辑屏 ===")
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (dm == null) {
            appendLine("(UserService 没有 DisplayManager Context，逻辑屏列表请在 App 侧查看)")
        } else {
            runCatching { dm.displays }.getOrNull()?.forEach { d ->
                val address = Reflect.call(d, "getAddress").getOrNull() as? String
                val type = Reflect.call(d, "getType").getOrNull() as? Int
                appendLine(
                    "  displayId=${d.displayId} name=${d.name} type=$type addr=$address " +
                        "state=${d.state} mode=${d.mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }}",
                )
            }
        }
        appendLine()
        appendLine("=== AIDL 调用日志（最近 30 条） ===")
        AidlCodec.callLog.takeLast(30).forEach { appendLine("  $it") }
    })

    // ------------------------------------------------------------------
    // 电源
    // ------------------------------------------------------------------

    override fun setDisplayPowerMode(displayId: Int, powerMode: Int): String {
        val reports = powerController.setPower(displayId, powerMode)
        val ok = reports.firstOrNull { it.ok }
        return clean(buildString {
            appendLine("displayId=$displayId 请求电源模式 ${DisplayPowerController.powerModeName(powerMode)}")
            // 机器可判定的结果行（上层据此判成功，不依赖 ✅ 子串 —— 这类判定出过假成功）
            appendLine("RESULT_OK=${ok != null}")
            appendLine()
            reports.forEach { appendLine(it.toText()) }
            appendLine()
            appendLine(if (ok != null) "成功通道: ${ok.channel}" else "全部通道失败")
            if (ok == null) appendLine("uid=${android.os.Process.myUid()}，请把以上完整信息发回排查")
        })
    }

    override fun getDisplayPowerMode(displayId: Int): String {
        val fromDumpsys = execCommand("dumpsys display | grep -A 3 'mDisplayId=$displayId'")
        val fromSc = runCatching {
            val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.getDisplay(displayId)?.state?.toString() ?: "(无 Context)"
        }.getOrNull() ?: "(读取失败)"
        return "displayId=$displayId  state(Display)=$fromSc\n\n--- dumpsys 片段 ---\n$fromDumpsys"
    }

    // ------------------------------------------------------------------
    // 分辨率 / Mode
    // ------------------------------------------------------------------

    override fun setForcedDisplaySize(displayId: Int, width: Int, height: Int): String {
        val before = resolutionController.readDisplaySize(displayId)
        val reports = resolutionController.setForcedDisplaySize(displayId, width, height)
        val after = resolutionController.readDisplaySize(displayId)
        val ok = reports.any { it.ok }
        return clean(buildString {
            appendLine("displayId=$displayId 逻辑尺寸覆盖 ${width}x$height")
            appendLine("RESULT_OK=$ok")
            appendLine("修改前: ${before.text}")
            reports.forEach { appendLine(it.toText()) }
            appendLine("修改后: ${after.text}")
            appendLine()
            appendLine("提示: 这是逻辑尺寸覆盖（不改硬件时序）。要切真实 Mode 请用 setUserPreferredDisplayMode。")
        })
    }

    override fun clearForcedDisplaySize(displayId: Int): String {
        val reports = resolutionController.clearForcedDisplaySize(displayId)
        val after = resolutionController.readDisplaySize(displayId)
        val ok = reports.any { it.ok }
        return clean(buildString {
            appendLine("displayId=$displayId 清除逻辑尺寸覆盖")
            appendLine("RESULT_OK=$ok")
            reports.forEach { appendLine(it.toText()) }
            appendLine("清除后: ${after.text}")
        })
    }

    override fun getDisplaySizes(displayId: Int): String = clean(buildString {
        appendLine("displayId=$displayId")
        appendLine("wm size -d $displayId:")
        appendLine(resolutionController.readDisplaySize(displayId).text)
        appendLine()
        appendLine("getBaseDisplaySize (IWindowManager): ${resolutionController.readBaseDisplaySize(displayId) ?: "(读取失败)"}")
        appendLine("getInitialDisplaySize (IWindowManager): ${resolutionController.readInitialDisplaySize(displayId) ?: "(读取失败)"}")
    })

    /**
     * 读回真实 Mode 状态。
     *
     * 这是判定「分辨率到底改没改」的唯一依据：
     * `setUserPreferredDisplayMode` 返回成功只说明没报错，**不代表时序变了**。
     * 早先版本只看调用是否抛异常，所以无法发现"请求被接受但实际没生效"。
     */
    override fun getModeState(displayId: Int): String = clean(buildString {
        appendLine("=== displayId=$displayId 的 Mode 状态 ===")
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(displayId)
        if (display == null) {
            appendLine("拿不到该 Display（可能已被系统移出枚举）")
        } else {
            val m = display.mode
            appendLine(
                "当前实际 Mode: " +
                    (m?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" } ?: "?") +
                    " (modeId=${m?.modeId})",
            )
            appendLine("supportedModes 数量: ${display.supportedModes?.size ?: 0}")
            display.supportedModes?.forEach {
                appendLine("  modeId=${it.modeId} ${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}")
            }
        }
        appendLine()
        appendLine("用户首选 Mode: " + (resolutionController.readUserPreferredMode(displayId) ?: "(读不到)"))
        appendLine("系统首选 Mode: " + (resolutionController.readSystemPreferredMode(displayId) ?: "(读不到)"))
    })

    /** 方向名（对应 AOSP `TreeNode.POSITION_*`）。 */
    private fun positionName(position: Int): String = when (position) {
        DisplayTopologyController.POSITION_LEFT -> "左侧"
        DisplayTopologyController.POSITION_TOP -> "上方"
        DisplayTopologyController.POSITION_RIGHT -> "右侧"
        DisplayTopologyController.POSITION_BOTTOM -> "下方"
        else -> "位置($position)"
    }
    /** 诊断 shell 环境：确认 wm / dumpsys 真的能跑（带绝对路径对照）。 */
    override fun probeShellEnvironment(): String = clean(buildString {
        appendLine("=== shell 环境自检 ===")
        appendLine("PATH = ${System.getenv("PATH")}")
        appendLine()
        for (cmd in listOf(
            "which wm",
            "which dumpsys",
            "/system/bin/wm size",
            "/system/bin/wm size -d 0",
            "wm size -d 0",
            "/system/bin/dumpsys display | head -n 3",
        )) {
            appendLine("--- $cmd ---")
            appendLine(execRaw(cmd).trim().ifEmpty { "(空输出)" })
            appendLine()
        }
    })

    /**
     * 一键还原：把本应用可能改动过的**所有**系统状态恢复。
     *
     * 顺序是刻意安排的：
     * 1. **先点亮内屏**（最高优先：保证用户看得见界面，不会黑屏）
     * 2. 内屏窗口模式还原 FULLSCREEN
     * 3. 解除所有输入设备关联（这一步只读+还原，不改危险状态）
     * 4. 清除音频输出固定
     * 5. 还原显示拓扑（若之前存过快照）
     * 6. 清除逻辑尺寸覆盖 + 重置用户首选 Mode
     */
    override fun restoreAll(): String {
        var allOk = true
        val sb = StringBuilder()
        sb.appendLine("=== 一键还原所有设置 ===")
        sb.appendLine("uid=${android.os.Process.myUid()}")
        sb.appendLine()

        // 1) 先点亮内屏（保证用户看得见）
        runCatching {
            val internal = resolveInternalDisplayIdViaContext()
            sb.appendLine("[1] 恢复内屏电源")
            val reports = powerController.setPower(internal, DisplayPowerController.POWER_MODE_NORMAL)
            if (reports.none { it.ok }) allOk = false
            reports.forEach { sb.appendLine("    " + it.toText()) }
        }.onFailure { allOk = false; sb.appendLine("[1] 失败: ${Reflect.describe(it)}") }
        sb.appendLine()

        // 2) 内屏窗口模式还原 FULLSCREEN
        runCatching {
            val internal = resolveInternalDisplayIdViaContext()
            sb.appendLine("[2] 还原内屏窗口模式为 FULLSCREEN")
            val result = mirrorController.setWindowingMode(
                internal,
                DisplayMirrorController.WINDOWING_MODE_FULLSCREEN,
            )
            if (!result.ok) allOk = false
            sb.appendLine("    " + result.toText())
        }.onFailure { allOk = false; sb.appendLine("[2] 失败: ${Reflect.describe(it)}") }
        sb.appendLine()

        // 3) 解除输入关联
        runCatching {
            sb.appendLine("[3] 解除所有输入设备关联")
            val reports = inputController.clearAllAssociations()
            if (reports.any { !it.ok }) allOk = false
            sb.appendLine("    处理条目数 = ${reports.size}")
            reports.filter { !it.ok }.take(5).forEach { sb.appendLine("    " + it.toText()) }
        }.onFailure { allOk = false; sb.appendLine("[3] 失败: ${Reflect.describe(it)}") }
        sb.appendLine()

        // 4) 清除音频输出固定
        runCatching {
            sb.appendLine("[4] 清除音频输出固定")
            audioController.unpinMediaOutput().forEach {
                if (!it.ok) allOk = false
                sb.appendLine("    " + it.toText())
            }
        }.onFailure { allOk = false; sb.appendLine("[4] 失败: ${Reflect.describe(it)}") }
        sb.appendLine()

        // 5) 还原显示拓扑
        runCatching {
            sb.appendLine("[5] 还原显示拓扑")
            if (!topologyController.isSupported()) {
                sb.appendLine("    本机不支持 DisplayTopology，跳过")
            } else {
                val snap = loadTopologySnapshot()
                if (snap == null) {
                    sb.appendLine("    没有拓扑快照（本应用从未改过拓扑），跳过")
                } else {
                    val restored = topologyController.restoreTopologyBytes(snap.second)
                    sb.appendLine("    " + restored.toText())
                    if (restored.ok) clearTopologySnapshot() else allOk = false
                }
            }
        }.onFailure { allOk = false; sb.appendLine("[5] 失败: ${Reflect.describe(it)}") }
        sb.appendLine()

        // 6) 清除尺寸覆盖与用户首选 Mode
        runCatching {
            sb.appendLine("[6] 清除逻辑尺寸覆盖 / 重置首选 Mode")
            val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: error("无法获取 DisplayManager")
            dm.displays.filter {
                val getType = Reflect.findMethod(it.javaClass, "getType")
                    ?: error("无法读取 Display ${it.displayId} 类型")
                getType.invoke(it) == 2
            }.forEach { d ->
                val sizeReports = resolutionController.clearForcedDisplaySize(d.displayId)
                if (sizeReports.none { it.ok }) allOk = false
                sizeReports.forEach {
                    sb.appendLine("    " + d.displayId + " " + it.toText())
                }
                val mode = resolutionController.resetUserPreferredMode(d.displayId)
                if (!mode.ok) allOk = false
                sb.appendLine("    " + d.displayId + " " + mode.toText())
            }
        }.onFailure { allOk = false; sb.appendLine("[6] 失败: ${Reflect.describe(it)}") }
        sb.appendLine()

        sb.appendLine("RESULT_OK=$allOk")
        sb.appendLine(if (allOk) "还原操作完成。" else "部分还原失败，请查看上方结果后重试。")
        return clean(sb.toString())
    }

    /** 在 UserService 侧解析内屏 displayId（枚举 → 默认屏）。 */
    private fun resolveInternalDisplayIdViaContext(): Int {
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displays = runCatching { dm?.displays }.getOrNull().orEmpty()
        displays.forEach { d ->
            val type = Reflect.call(d, "getType").getOrNull() as? Int
            if (type == com.paddisplay.app.display.DisplayDetector.DisplayType.INTERNAL) return d.displayId
        }
        return android.view.Display.DEFAULT_DISPLAY
    }

    /**
     * 镜像状态探针。
     *
     * 这是定位「4K 显示器有黑边、分辨率设不上」的关键：
     * `windowingMode == FULLSCREEN` **不等于**没在镜像。
     * AOSP 的 `DisplayContent.shouldBeMirrored()` 主要看两件事：
     *
     * ```
     * shouldBeMirrored() = !mDisplayWindowSettings.shouldBeEnabled(...)
     *                      || (shouldForceDesktopMode()
     *                          && windowingMode == WINDOWING_MODE_FULLSCREEN)
     * ```
     *
     * 其中 `DisplayManagerService.shouldForceDesktopMode()`：
     *
     * ```
     * mDisplayId != DEFAULT_DISPLAY
     *   && resources.getBoolean(config_isDesktopModeSupported)      // 设备能力
     *   && Settings.Global.getInt(development_force_desktop_mode_on_external_displays) == 1
     * ```
     *
     * 所以只要这两项同时为真，外接屏在 FULLSCREEN 下就会被强制镜像 ——
     * 跟着内屏的分辨率走，于是"分辨率设不上 + 黑边"。
     */
    override fun probeMirrorState(externalDisplayId: Int): String = clean(buildString {
        appendLine("=== 镜像状态探针（displayId=$externalDisplayId）===")
        val ctx = injectedContext

        // ---- 1) 全局设置：是否强制桌面模式 ----
        val forceDesktopKey = "development_force_desktop_mode_on_external_displays"
        val globalValue = runCatching {
            android.provider.Settings.Global.getInt(ctx?.contentResolver, forceDesktopKey, -1)
        }.getOrDefault(-999)
        appendLine("Settings.Global.$forceDesktopKey = $globalValue")
        appendLine("  （1 = 强制在外接屏开启桌面模式，同时也是 AOSP 的镜像触发条件之一）")
        appendLine()

        // ---- 2) 设备能力：config_isDesktopModeSupported ----
        val supported = runCatching {
            val res = ctx?.resources ?: return@runCatching null
            val id = res.getIdentifier("config_isDesktopModeSupported", "bool", "android")
            if (id != 0) res.getBoolean(id) else null
        }.getOrNull()
        appendLine("config_isDesktopModeSupported = ${supported ?: "(读不到)"}")
        appendLine()

        // ---- 3) DisplayInfo 实际尺寸（黑边的直接证据）----
        appendLine("--- DisplayInfo 实际尺寸 ---")
        val dm = ctx?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(externalDisplayId)
        if (display == null) {
            appendLine("拿不到该 Display")
        } else {
            appendLine("Display.getName() = ${display.name}")
            val m = display.mode
            appendLine("Display.getMode() = " + (m?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate} id=${it.modeId}" } ?: "?"))
            appendLine("Display.getState() = ${display.state}")
            appendLine("Display.getFlags() = 0x${Integer.toHexString(display.flags)}")
            appendLine()
            // DisplayInfo 是隐藏类，用反射读它的公共字段
            val info = readDisplayInfo(display.displayId)
            if (info == null) {
                appendLine("DisplayInfo 反射读取失败")
            } else {
                for (f in listOf(
                    "logicalWidth", "logicalHeight", "appWidth", "appHeight",
                    "logicalDensityDpi", "smallestNominalAppWidth", "smallestNominalAppHeight",
                    "largestNominalAppWidth", "largestNominalAppHeight",
                    "type", "displayId", "rotation", "address",
                )) {
                    val v = Reflect.getField(info, f).getOrNull()
                    if (v != null) appendLine("  DisplayInfo.$f = $v")
                }
                appendLine()
                appendLine("判读提示：若 logicalWidth/Height 明显小于显示器物理分辨率，")
                appendLine("或 appWidth/Height 与内屏一致，说明外接屏正在**镜像**内屏，")
                appendLine("画面被系统放大到 4K 面板 -> 出现黑边、且分辨率设不上。")
            }
        }
        appendLine()
        appendLine("--- dumpsys 相关片段 ---")
        appendLine(execRaw("/system/bin/dumpsys display | grep -i -A2 'mDisplayId=$externalDisplayId' | head -n 40"))
    })

    // ------------------------------------------------------------------
    // 输入路由
    // ------------------------------------------------------------------

    private val inputController: InputRoutingController by lazy {
        InputRoutingController(
            inputManagerBinder = PhysicalDisplayAccess.inputManagerBinder(),
            shellRunner = ::execRaw,
        )
    }

    override fun listInputDevices(): String = clean(inputController.describe())

    @Synchronized
    override fun bindInputToDisplay(displayId: Int): String = clean(buildString {
        appendLine("=== 把外接输入设备绑定到 displayId=$displayId ===")
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(displayId)
        if (display == null) {
            appendLine("RESULT_OK=false")
            appendLine("拿不到该 Display")
            return@buildString
        }
        // 只绑鼠标/指针；**键盘默认不绑** —— Android 的设备关联是静态的，
        // 绑了键盘它就只往外屏送键事件；而键事件走焦点窗口，
        // 不绑反而能让两块屏按焦点各自接收键盘输入。
        val cleared = inputController.clearAllAssociations()
        val reports = inputController.bindAllExternalInputToDisplay(
            display = display,
            includeMouse = true,
            includeKeyboard = false,
        )
        val ok = reports.lastOrNull { it.channel == "读回验证" }?.ok == true
        appendLine("RESULT_OK=$ok")
        appendLine()
        (cleared + reports).forEach { appendLine(it.toText()) }
        appendLine("此结果仅验证输入设备关联；外屏可见原生光标、Moonlight 相对鼠标和跨屏流转仍需分别实测。")
        if (!ok) {
            appendLine()
            appendLine("说明：绑定失败时鼠标/触摸仍只能作用于默认屏。")
            appendLine("这是 Android 默认行为 —— 也正因如此，系统才用「复制模式」回避了它。")
        }
    })

    override fun clearInputAssociations(): String = clean(buildString {
        appendLine("=== 解除输入关联，恢复默认 ===")
        val reports = inputController.clearAllAssociations()
        appendLine("RESULT_OK=${reports.isNotEmpty() && reports.all { it.ok }}")
        appendLine()
        reports.forEach { appendLine(it.toText()) }
    })

    // ------------------------------------------------------------------
    // 多屏拓扑（左右关系）
    // ------------------------------------------------------------------

    private val topologyController: DisplayTopologyController by lazy {
        DisplayTopologyController(displayService())
    }

    override fun probeDisplayTopology(): String = clean(topologyController.describe())

    /**
     * 拓扑快照文件。
     *
     * `setDisplayTopology` 是整体替换，**必须先存原始值才能还原**。
     * 存在 UserService 进程的私有目录里，作为「一键还原」的依据。
     */
    private val topologySnapshotFile: java.io.File?
        get() = injectedContext?.getFileStreamPath("display_topology_snapshot.bin")

    private fun saveTopologySnapshot() {
        runCatching {
            val result = topologyController.snapshotTopologyBytes() ?: return
            val (ok, bytes) = result
            if (!ok) return
            val f = topologySnapshotFile ?: return
            if (f.exists()) return // 已有快照就不覆盖，保留最初的原始值
            java.io.DataOutputStream(f.outputStream()).use { out ->
                if (bytes == null) {
                    out.writeBoolean(false)
                } else {
                    out.writeBoolean(true)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
            AidlCodec.callLog.add("已保存显示拓扑快照（${bytes?.size ?: 0} 字节）")
        }.onFailure {
            AidlCodec.callLog.add("保存拓扑快照失败：${Reflect.describe(it)}")
        }
    }

    private fun loadTopologySnapshot(): Pair<Boolean, ByteArray?>? {
        val f = topologySnapshotFile ?: return null
        if (!f.exists()) return null
        return runCatching {
            java.io.DataInputStream(f.inputStream()).use { input ->
                val hasBytes = input.readBoolean()
                if (!hasBytes) {
                    true to null
                } else {
                    val n = input.readInt()
                    val buf = ByteArray(n)
                    input.readFully(buf)
                    true to buf
                }
            }
        }.getOrNull()
    }

    private fun clearTopologySnapshot() {
        runCatching { topologySnapshotFile?.delete() }
    }

    override fun setDisplayTopologyLayout(
        primaryDisplayId: Int,
        otherDisplayId: Int,
        position: Int,
        primaryWidth: Int,
        primaryHeight: Int,
        otherWidth: Int,
        otherHeight: Int,
    ): String = clean(buildString {
        appendLine("=== 设置多屏左右布局 ===")
        // 改拓扑前先存原始快照，供「一键还原」用（只存第一次）
        saveTopologySnapshot()
        appendLine("原点显示器 displayId=$primaryDisplayId (${primaryWidth}x$primaryHeight)")
        appendLine("另一块 displayId=$otherDisplayId (${otherWidth}x$otherHeight) 放在 ${positionName(position)}")
        appendLine()
        val reports = topologyController.setLayout(
            primaryDisplayId = primaryDisplayId,
            otherDisplayId = otherDisplayId,
            position = position,
            primarySize = primaryWidth to primaryHeight,
            otherSize = otherWidth to otherHeight,
        )
        val ok = reports.any { it.channel == "读回验证" && it.ok }
        appendLine("RESULT_OK=$ok")
        appendLine()
        reports.forEach { appendLine(it.toText()) }
        if (!ok) {
            appendLine()
            appendLine("说明：本机的 DisplayTopology feature flag 很可能关闭，")
            appendLine("服务端会静默忽略写入。这不是权限问题，是 ROM 未启用该能力。")
        }
    })

    /**
     * 把应用启动到指定桌面。
     *
     * `am start --display <id>` 是系统自带能力，用于实现"两个桌面"：
     * 把应用分别启动到内屏和外接屏，各自独立运行。
     */
    override fun launchAppOnDisplay(displayId: Int, component: String, packageName: String): String =
        clean(buildString {
            appendLine("=== 把应用启动到 displayId=$displayId ===")
            val cmd = when {
                component.isNotBlank() -> "/system/bin/am start --display $displayId -n $component"
                packageName.isNotBlank() -> "/system/bin/am start --display $displayId -p $packageName"
                else -> "/system/bin/am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            }
            appendLine("命令: $cmd")
            appendLine()
            val out = execRaw(cmd).trim()
            appendLine(out.ifEmpty { "(空输出)" })
            val ok = !out.contains("Error") && !out.contains("Exception") && out.isNotEmpty()
            appendLine()
            appendLine("RESULT_OK=$ok")
        })

    // ------------------------------------------------------------------
    // 坐标空间（光标错位排查）
    // ------------------------------------------------------------------

    private val coordProbe: CoordinateSpaceProbe by lazy { CoordinateSpaceProbe(::execRaw) }

    override fun probeCoordinateSpaces(displayIdsCsv: String): String = clean(buildString {
        val ids = displayIdsCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
        appendLine(coordProbe.describe(if (ids.isEmpty()) listOf(0) else ids))
        appendLine()
        appendLine("--- Display API 读数（仅供对照，已被应用缩放污染）---")
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        (if (ids.isEmpty()) listOf(0) else ids).forEach { id ->
            val d = dm?.getDisplay(id)
            if (d == null) {
                appendLine("  displayId=$id: (取不到)")
            } else {
                val p = android.graphics.Point()
                val m = android.util.DisplayMetrics()
                runCatching { d.getRealSize(p) }
                runCatching { d.getMetrics(m) }
                appendLine(
                    "  displayId=$id getRealSize=${p.x}x${p.y} getMetrics=${m.widthPixels}x${m.heightPixels} " +
                        "density=${m.densityDpi}",
                )
            }
        }
    })

    override fun injectTapOnDisplay(displayId: Int, x: Int, y: Int): String = clean(buildString {
        appendLine("=== 在 displayId=$displayId 注入点击 ($x, $y) ===")
        val (ok, detail) = coordProbe.tap(displayId, x, y)
        appendLine("RESULT_OK=$ok")
        appendLine(detail)
        appendLine()
        appendLine("说明：注入坐标必须用「dumpsys window displays 的 cur=WxH」那套空间，")
        appendLine("不能用 Display.getRealSize()/getMetrics()（会被应用兼容缩放污染）。")
    })

    // ------------------------------------------------------------------
    // 桌面模式 / 自由窗口（DeX / TNT 类桌面的机制）
    // ------------------------------------------------------------------

    /**
     * 桌面模式相关的 Global settings 键。
     *
     * 这些就是开发者选项里的勾选，AOSP WMS 的 `SettingsObserver` 直接监听它们：
     * - `updateForceDesktopModeOnExternalDisplays()`
     * - `updateFreeformWindowManagement()`
     *
     * 也就是说：**写这两个键就等于帮用户在开发者选项里打勾**，无需 root
     * （需要 WRITE_SECURE_SETTINGS，shell 持有）。
     */
    private val desktopModeKeys = listOf(
        "development_force_desktop_mode_on_external_displays",
        "development_enable_freeform_windows_support",
        "development_force_resizable_activities",
        "development_enable_non_resizable_multi_window",
        "development_override_desktop_experience_features",
    )

    override fun probeDesktopMode(): String = clean(buildString {
        val ctx = injectedContext
        val resolver = ctx?.contentResolver
        appendLine("=== 桌面模式 / 自由窗口 探测 ===")
        appendLine("uid=${android.os.Process.myUid()}")
        appendLine()

        appendLine("--- Global settings 当前值（shell: settings get global）---")
        desktopModeKeys.forEach { key ->
            val (v, err) = shellGetGlobal(key)
            val label = when (v) {
                "1" -> "已开启"
                "0" -> "已关闭"
                null -> "键不存在 / 读取失败"
                else -> "其它值"
            }
            appendLine("  $key = ${v ?: "(无)"}  ($label)")
            if (err.isNotEmpty()) appendLine("      $err")
        }
        appendLine()

        // 关键诊断：全量 dump 后搜关键词，判断该键是否真的存在于系统注册表
        appendLine("--- 关键诊断：settings list global 里搜 desktop / freeform / multi_window ---")
        val dump = execRaw("/system/bin/settings list global")
        appendLine("  (settings list global 输出 ${dump.length} 字符)")
        val hits = dump.lines().filter { line ->
            listOf("desktop", "freeform", "multi_window", "resizable", "force_desktop")
                .any { line.contains(it, ignoreCase = true) }
        }
        if (hits.isEmpty()) {
            appendLine("  ❌ 一个都没搜到 —— 说明这些键在 ColorOS 的 SettingsProvider 里**根本未注册**")
            appendLine("     （不是权限问题，是 ROM 移除了这些开发者选项）")
        } else {
            appendLine("  搜到 ${hits.size} 条：")
            hits.forEach { appendLine("    $it") }
        }
        appendLine()
        appendLine("--- settings 命令本身是否可用 ---")
        appendLine("  which settings: " + execRaw("which settings").trim().ifEmpty { "(未找到)" })
        appendLine("  settings --help 前 3 行: " +
            execRaw("/system/bin/settings --help").lines().take(3).joinToString(" / ").take(200))
        appendLine()

        appendLine("--- 系统能力（编译期，不可改）---")
        val pm = ctx?.packageManager
        listOf(
            "android.software.freeform_window_management",
            "android.software.multi_window",
            "android.hardware.type.pc",
        ).forEach { f ->
            val has = runCatching { pm?.hasSystemFeature(f) }.getOrDefault(false)
            appendLine("  $f = $has")
        }
        appendLine("  config_isDesktopModeSupported = " + runCatching {
            val res = ctx?.resources ?: return@runCatching null
            val id = res.getIdentifier("config_isDesktopModeSupported", "bool", "android")
            if (id != 0) res.getBoolean(id) else null
        }.getOrNull())
        appendLine()

        appendLine("--- 参考实现的做法 ---")
        appendLine("  fox0001/android-desktop-mode 的 README 指出：")
        appendLine("  开发者选项里勾选「启用可自由调整的窗口」+「强制使用桌面模式」，")
        appendLine("  即可启用系统内置的桌面模式（App 可自由拖动、调整窗口大小）。")
        appendLine()
        appendLine("--- ⚠️ 关于鼠标指针 ---")
        appendLine("  AOSP WindowManagerService 里对 mForceDesktopModeOnExternalDisplays 的注释：")
        appendLine("    \"Enable system decorations and IME on external screen.\"")
        appendLine("    \"TODO: Show mouse pointer on external screen.\"")
        appendLine("  即：桌面模式本身**并不**解决「鼠标指针显示在外接屏」，")
        appendLine("  更不解决「指针跨两块屏」—— 后者需要 DisplayTopology（本机被关闭）。")
    })

    /**
     * 读一个 Global 设置（走 shell `settings get`，与 adb 同一条路）。
     *
     * 为什么不再用 ContentResolver：ContentResolver 读缺失的键只会返回默认值，
     * 而写入抛出的 SecurityException 被我们自己吞掉了，导致 -999 -> -999
     * 这种没有信息量的输出。改用 shell 命令后，成败与 stderr 都看得见。
     */
    private fun shellGetGlobal(key: String): Pair<String?, String> {
        val out = execRaw("/system/bin/settings get global $key")
        val trimmed = out.trim()
        val err = if (trimmed.isEmpty() || trimmed.equals("null", true)) {
            "键不存在或读取失败（输出：${if (trimmed.isEmpty()) "(空)" else trimmed}）"
        } else {
            ""
        }
        return (trimmed.ifEmpty { null }) to err
    }

    /** 写一个 Global 设置。返回 (成功, 详情)。 */
    private fun shellPutGlobal(key: String, value: Int): Triple<Boolean, String, String> {
        val cmd = "/system/bin/settings put global $key $value"
        val out = execRaw(cmd).trim()
        // 写回验证
        val now = execRaw("/system/bin/settings get global $key").trim()
        val ok = now == value.toString()
        val detail = buildString {
            append("命令: $cmd")
            append(" | 输出: ${if (out.isEmpty()) "(空)" else out.take(200)}")
            append(" | 读回: ${if (now.isEmpty()) "(空)" else now}")
        }
        return Triple(ok, detail, if (ok) "" else "写入后读回不匹配")
    }

    override fun setDesktopMode(enable: Boolean): String = clean(buildString {
        val want = if (enable) 1 else 0
        appendLine("=== ${if (enable) "开启" else "关闭"}桌面模式相关设置 ===")
        appendLine("uid=${android.os.Process.myUid()}")
        appendLine()
        appendLine("说明：本方法走 shell `settings put global`（等同 adb shell settings put），")
        appendLine("不再用 ContentResolver —— 后者会把异常吞掉，导致看不到失败原因。")
        appendLine()

        var allOk = true
        desktopModeKeys.forEach { key ->
            val (before, beforeErr) = shellGetGlobal(key)
            val (ok, detail, why) = shellPutGlobal(key, want)
            val (after, _) = shellGetGlobal(key)
            if (!ok) allOk = false
            appendLine("${if (ok) "✅" else "❌"} $key")
            appendLine("    写入前: ${before ?: "(无)"}${if (beforeErr.isNotEmpty()) "  ← $beforeErr" else ""}")
            appendLine("    $detail")
            appendLine("    写入后: ${after ?: "(无)"}")
            if (why.isNotEmpty()) appendLine("    失败原因: $why")
        }
        appendLine()
        appendLine("RESULT_OK=$allOk")
        appendLine()
        if (allOk) {
            appendLine("已写入。WMS 的 SettingsObserver 会实时响应这些键，")
            appendLine("但桌面形态需要**重新插拔外接屏或重启**才会重新计算。")
            appendLine()
            appendLine("下一步：重新插拔外接屏 → 再用 MouseFlow 探测原生指针所在屏。")
        } else {
            appendLine("写入失败。请把上面每行的完整输出发回 —— ")
            appendLine("特别是「命令」与「输出」两段，里面会有 settings 的真实报错")
            appendLine("（例如 SecurityException / BadUser / unknown setting）。")
            appendLine()
            appendLine("注意：如果报错是 unknown setting，说明 ColorOS 移除了该键的注册表项；")
            appendLine("若是 SecurityException，说明被 READ_ONLY 或权限策略挡住。")
            appendLine("两者都意味着这条路在 ColorOS 上被厂商关闭，需要另想办法。")
        }
    })

    // ------------------------------------------------------------------
    // MouseFlow 第一轮实验：原生鼠标光标所在 Display
    // ------------------------------------------------------------------

    private val pointerController: PointerDisplayController by lazy {
        PointerDisplayController(
            inputManagerBinder = PhysicalDisplayAccess.inputManagerBinder(),
            shellRunner = ::execRaw,
        )
    }

    /** 从 `dumpsys display` 取每块屏的 windowingMode（AOSP 判据需要它）。 */
    private fun windowingModesFromDumpsys(): Map<Int, Int> {
        val dump = execRaw("/system/bin/dumpsys display")
        val result = mutableMapOf<Int, Int>()
        var currentId: Int? = null
        dump.lines().forEach { line ->
            Regex("""displayId[= ](\d+)""").find(line)?.let {
                currentId = it.groupValues[1].toIntOrNull()
            }
            val id = currentId ?: return@forEach
            Regex("""windowingMode[= ]([A-Za-z]+)\((\d+)\)""").find(line)?.let {
                result[id] = it.groupValues[2].toIntOrNull() ?: return@let
            }
        }
        return result
    }

    override fun probePointerDisplay(): String = clean(buildString {
        appendLine("=========== MouseFlow 探测 ===========")
        appendLine()

        // 1) 物理鼠标枚举
        appendLine("--- 物理鼠标设备 ---")
        val mice = runCatching { pointerController.listMice() }.getOrElse { t ->
            appendLine("  枚举失败: ${Reflect.describe(t)}")
            emptyList()
        }
        if (mice.isEmpty()) {
            appendLine("  （没有检测到任何鼠标类设备）")
        } else {
            mice.forEach { m ->
                appendLine("  Device ID: ${m.id}")
                appendLine("    Name:     ${m.name}")
                appendLine("    Sources:  0x${m.sources.toString(16)}")
                appendLine("    Vendor:   ${m.vendorId}  Product: ${m.productId}")
                appendLine("    External: ${m.isExternal}")
                appendLine("    AssocDisplayId: ${m.associatedDisplayId ?: "(无)"}")
                appendLine("    RelativeAxes: ${m.hasRelativeAxes}  ← Moonlight 相对鼠标模式相关")
            }
        }
        appendLine()

        // 2) 显示器状态
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displays = runCatching { dm?.displays }.getOrNull().orEmpty()
        appendLine("--- 当前显示器 ---")
        val states = mutableMapOf<Int, Boolean>()
        displays.forEach { d ->
            val type = Reflect.call(d, "getType").getOrNull() as? Int ?: -1
            val state = runCatching { Reflect.call(d, "getState").getOrNull() as? Int }.getOrNull() ?: -1
            // Display.STATE_OFF = 1
            states[d.displayId] = state != 1
            appendLine("  Display ${d.displayId}: name=${d.name} type=$type state=$state")
        }
        appendLine()

        // 3) windowingMode
        val modes = runCatching { windowingModesFromDumpsys() }.getOrDefault(emptyMap())
        appendLine("--- windowingMode（AOSP 判据：FREEFORM=5）---")
        modes.forEach { (id, mode) -> appendLine("  Display $id -> windowingMode=$mode") }
        appendLine()

        // 4) 分析
        appendLine(runCatching { pointerController.analyze(states, modes) }
            .getOrElse { "分析失败: ${Reflect.describe(it)}" })

        appendLine()
        appendLine("--- 当前指针位置读取 ---")
        val pos = pointerController.currentPosition(0)
        appendLine("  dumpsys 解析结果: ${pos ?: "(读不到，不编造)"}")

        appendLine()
        appendLine("--- ADB 辅助诊断命令（可复制到 PC 上跑）---")
        appendLine("  adb shell dumpsys input | grep -i -E \"pointer|display|viewport|mouse\"")
        appendLine("  adb shell dumpsys display")
    })

    override fun forcePointerToDisplay(displayId: Int): String = clean(buildString {
        appendLine("=== 强制指针到 Display $displayId ===")
        appendLine()

        // 先确定目标屏的坐标空间（必须从系统侧量）
        val probe = CoordinateSpaceProbe(::execRaw)
        val space = runCatching { probe.injectionSpace(displayId) }.getOrNull()
        if (space == null) {
            appendLine("RESULT_OK=false")
            appendLine("取不到 Display $displayId 的坐标空间（dumpsys window displays 无 cur=）")
            appendLine("→ 无法安全计算落点，拒绝盲猜坐标")
            return@buildString
        }
        appendLine("目标屏坐标空间: ${space.label}（来源 ${space.raw}）")

        // 落点：屏幕中心
        val x = space.width / 2
        val y = space.height / 2
        appendLine("落点: ($x, $y)")
        appendLine()

        val r = runCatching { pointerController.moveTo(displayId, x, y) }.getOrElse { t ->
            PointerDisplayController.Report(false, "异常: ${Reflect.describe(t)}")
        }
        appendLine("RESULT_OK=${r.ok}")
        appendLine(r.toText())

        if (!r.ok) {
            appendLine()
            appendLine("--- 失败排查 ---")
            appendLine("本方法走的是 injectInputEvent + setDisplayId（可调用）。")
            appendLine("若它失败，说明注入这条路被挡（缺 INJECT_EVENTS 或 ROM 限制）。")
            appendLine()
            appendLine("但请注意：**原生光标能否显示在外屏，取决于另外两个 Global 设置**")
            appendLine("（桌面模式 + 自由窗口），不是靠这个方法。")
            appendLine("先看「探测」输出里的分析结论。")
        }
    })

    // ------------------------------------------------------------------
    // 指针注入（触控板方案）
    // ------------------------------------------------------------------

    private val pointerInjector: PointerInjector by lazy {
        PointerInjector(
            inputManagerBinder = PhysicalDisplayAccess.inputManagerBinder(),
            shellRunner = ::execRaw,
        )
    }

    override fun injectPointer(displayId: Int, action: Int, x: Int, y: Int, button: Int): String =
        clean(buildString {
            val r = pointerInjector.injectDownUp(displayId, action, x, y, button)
            appendLine("RESULT_OK=${r.ok}")
            appendLine("displayId=$displayId action=$action ($x,$y) button=$button -> ${r.detail}")
        })

    override fun injectPointerMove(displayId: Int, x: Int, y: Int): String = clean(buildString {
        val r = pointerInjector.injectMove(displayId, x, y)
        appendLine("RESULT_OK=${r.ok}")
        appendLine("displayId=$displayId move ($x,$y) -> ${r.detail}")
    })

    // ------------------------------------------------------------------
    // 应用启动器（外接屏的「开始菜单」）
    // ------------------------------------------------------------------

    /**
     * 列出所有可启动应用，输出格式：`包名|标签|组件`（每行一条）。
     *
     * 为什么需要它：早先启动时**写死了 `com.android.settings/.Settings`**，
     * 所以外接屏上只能打开「设置」—— 这是我的实现缺陷。
     * 要开别的应用，必须先拿到它们的 launcher Activity 组件。
     */
    override fun listLaunchableApps(): String = clean(buildString {
        val ctx = injectedContext
        if (ctx == null) {
            appendLine("RESULT_OK=false")
            appendLine("拿不到 Context")
            return@buildString
        }
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolved = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }.getOrElse { emptyList() }

        val rows = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val cls = ri.activityInfo?.name ?: return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            if (pkg == ctx.packageName) return@mapNotNull null
            Triple(pkg, label, "$pkg/$cls")
        }.distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        appendLine("RESULT_OK=true")
        appendLine("共 ${rows.size} 个可启动应用")
        appendLine()
        rows.forEach { (pkg, label, comp) -> appendLine("$pkg|$label|$comp") }
    })

    /**
     * 把指定包名的主 Activity 启动到目标屏。
     *
     * 用 `am start --display N -n <组件>`。
     * 早先用 `-p <包名>` 对 `am start` 并不可靠（它期望 component），
     * 所以只有显式给了 `-n` 的「设置」能成功 —— 这就是"只能开设置"的原因。
     */
    override fun launchPackageOnDisplay(displayId: Int, packageName: String): String = clean(buildString {
        appendLine("=== 启动 $packageName 到 displayId=$displayId ===")
        val ctx = injectedContext
        if (ctx == null) {
            appendLine("RESULT_OK=false")
            appendLine("拿不到 Context，无法解析 launcher 组件")
            return@buildString
        }
        val pm = ctx.packageManager
        val component = runCatching {
            @Suppress("DEPRECATION")
            val i = pm.getLaunchIntentForPackage(packageName)
            i?.component?.let { "${it.packageName}/${it.className}" }
        }.getOrNull()

        if (component == null) {
            appendLine("RESULT_OK=false")
            appendLine("该包没有可直接启动的 launcher Activity（可能是服务类应用或已被禁用）")
            return@buildString
        }
        appendLine("解析到组件: $component")

        val cmd = "/system/bin/am start --display $displayId -n $component"
        appendLine("命令: $cmd")
        appendLine()
        val out = execRaw(cmd).trim()
        appendLine(out.ifEmpty { "(空输出)" })
        val ok = !out.contains("Error") && !out.contains("Exception") &&
            !out.contains("does not exist") && out.isNotEmpty()
        appendLine()
        appendLine("RESULT_OK=$ok")
        if (!ok) {
            appendLine("提示：部分 ROM 限制把第三方应用启动到副屏；若报权限/安全错误即属此类。")
        }
    })

    /**
     * 在外接屏上真实启动一个 Activity（决定性测试）。
     *
     * 用系统自带的 `am start --display`，完全绕开本应用的实现。
     */
    override fun launchOnDisplay(displayId: Int, component: String): String = clean(buildString {
        appendLine("=== 在外接屏 displayId=$displayId 启动 $component ===")
        val cmd = "/system/bin/am start --display $displayId -n $component"
        appendLine("命令: $cmd")
        appendLine()
        appendLine(execRaw(cmd).trim().ifEmpty { "(空输出)" })
        appendLine()
        appendLine("判读：")
        appendLine("  · 若外接屏上出现填满 4K 的独立窗口 → 扩展在系统层面成立，")
        appendLine("    黑边来自上层的镜像/投屏，需要在 ColorOS 多屏设置里关闭投屏。")
        appendLine("  · 若仍然带黑边 → ColorOS 的多屏服务在更上层接管了外接屏。")
    })

    /**
     * 找 ColorOS 的多屏 / 投屏 / 外接显示相关设置页入口。
     *
     * 外接屏的「复制 / 扩展」在 ColorOS 上由私有服务
     * `dynamicallyConfigViewer` 那套逻辑控制，找不到公开 API，
     * 所以直接把这些设置页挖出来让用户自己进去看。
     */
    override fun findDisplaySettingsActivities(): String = clean(buildString {
        appendLine("=== ColorOS 多屏/投屏 相关设置页 ===")
        val keywords = "projection|cast|screen|display|mirror|mira|multiscreen|multi_screen|screencast|wireless"
        appendLine("--- 含关键词的 Activity ---")
        appendLine(
            execRaw("/system/bin/cmd package query-activities --brief -a android.intent.action.MAIN " +
                "| grep -iE '$keywords' | head -n 60").trim().ifEmpty { "(无匹配)" },
        )
        appendLine()
        appendLine("--- OPPO/oplus 包中含关键词的组件 ---")
        appendLine(
            execRaw("/system/bin/pm list packages | grep -iE 'oplus|oppo|coloros|screencast|cast' " +
                "| head -n 40").trim().ifEmpty { "(无匹配)" },
        )
        appendLine()
        appendLine("--- 设置里所有含关键词的入口 ---")
        appendLine(
            execRaw("/system/bin/dumpsys package com.android.settings " +
                "| grep -iE 'Activity' | grep -iE '$keywords' | head -n 40").trim().ifEmpty { "(无匹配)" },
        )
    })

    /** 反射读 DisplayManagerGlobal.getDisplayInfo(displayId)（隐藏 API）。 */
    private fun readDisplayInfo(displayId: Int): Any? = runCatching {
        val clazz = Reflect.classForName("android.hardware.display.DisplayManagerGlobal")
        val getInstance = Reflect.findMethod(clazz, "getInstance") ?: return null
        val global = getInstance.invoke(null) ?: return null
        val m = Reflect.findMethod(global.javaClass, "getDisplayInfo", Int::class.javaPrimitiveType) ?: return null
        m.invoke(global, displayId)
    }.getOrNull()

    /**
     * 开关「在外接屏强制桌面模式」。
     *
     * 这条全局设置是 AOSP 让外接屏被强制镜像的条件之一，也是
     * 「4K 显示器有黑边、分辨率设不上」最可能的总开关。
     */
    override fun setForceDesktopMode(enable: Boolean): String {
        val key = "development_force_desktop_mode_on_external_displays"
        val resolver = injectedContext?.contentResolver
        if (resolver == null) {
            return clean("RESULT_OK=false\n拿不到 ContentResolver，无法写入该设置")
        }
        return clean(buildString {
            val old = runCatching {
                android.provider.Settings.Global.getInt(resolver, key, -1)
            }.getOrDefault(-999)
            appendLine("设置项: Settings.Global.$key")
            appendLine("原值: $old   目标值: ${if (enable) 1 else 0}")
            val wrote = runCatching {
                android.provider.Settings.Global.putInt(resolver, key, if (enable) 1 else 0)
            }.getOrDefault(false)
            val now = runCatching {
                android.provider.Settings.Global.getInt(resolver, key, -1)
            }.getOrDefault(-999)
            val want = if (enable) 1 else 0
            val ok = wrote && now == want
            appendLine("RESULT_OK=$ok")
            appendLine("写入返回: $wrote   读回值: $now")
            if (!ok) {
                appendLine()
                appendLine("写入失败或被系统拒绝。该设置需要 WRITE_SECURE_SETTINGS，")
                appendLine("Shizuku 的 shell 身份应当持有；若被 ColorOS 拦截会在这里体现。")
            } else {
                appendLine()
                appendLine("已改。请重新插拔外接屏（或重启）让显示策略重新计算，")
                appendLine("然后再次查看镜像探针与分辨率列表。")
            }
        })
    }

    override fun setUserPreferredDisplayMode(
        displayId: Int,
        modeId: Int,
        width: Int,
        height: Int,
        refreshRate: Float,
    ): String {
        val info = com.paddisplay.app.display.DisplayModeInfo(
            modeId = modeId,
            physicalWidth = width,
            physicalHeight = height,
            refreshRate = refreshRate,
        )
        val reports = resolutionController.changeMode(displayId, info)
        val modeOk = reports.any { it.ok }

        // 真实 Mode 失败时，顺带报告一次逻辑尺寸通道是否可用，
        // 让上层的「回退到逻辑尺寸覆盖」有依据（而不是自己去猜）。
        return clean(buildString {
            appendLine("displayId=$displayId 请求切换 Mode -> ${info.label} (modeId=$modeId)")
            appendLine("RESULT_OK=$modeOk")
            appendLine()
            reports.forEach { appendLine(it.toText()) }
            if (!modeOk) {
                appendLine()
                appendLine("真实 Mode 切换失败；逻辑尺寸覆盖通道仍可用作回退。")
            }
        })
    }

    override fun resetUserPreferredDisplayMode(displayId: Int): String =
        resolutionController.resetUserPreferredMode(displayId).toText()

    // ------------------------------------------------------------------
    // 音频输出路由
    // ------------------------------------------------------------------

    private val audioController: AudioRoutingController by lazy {
        AudioRoutingController(
            contextProvider = { injectedContext },
            audioServiceBinder = PhysicalDisplayAccess.audioServiceBinder(),
        )
    }

    override fun listAudioOutputs(): String = clean(audioController.describe())

    override fun getCurrentAudioRouting(): String = clean(buildString {
        appendLine("=== 当前音频路由 ===")
        appendLine("media(pin): ${audioController.currentMediaOutput()?.let { "id=${it.id} type=${it.type}" } ?: "(读不到)"}")
        appendLine("comm: ${audioController.currentCommunicationOutput()}")
        appendLine()
        appendLine("uid=${android.os.Process.myUid()}")
        appendLine()
        appendLine("=== 可用输出 ===")
        audioController.availableOutputs().forEach { o ->
            appendLine(
                "  id=${o.id} type=${o.type} name=${o.name} addr=${o.address}" +
                    (if (o.isCurrentMedia) "  <current-media>" else "") +
                    (if (o.isDisplayLike) "  <display-like>" else ""),
            )
        }
    })

    override fun setAudioOutputDevice(deviceId: Int, pinMedia: Boolean, pinComm: Boolean): String =
        clean(buildString {
            appendLine("请求把音频输出切到 deviceId=$deviceId (pinMedia=$pinMedia, pinComm=$pinComm)")
            appendLine("uid=${android.os.Process.myUid()}")
            appendLine()

            val reports = mutableListOf<AudioRoutingController.Report>()
            var mediaOk = false
            if (pinMedia) {
                val mediaReports = audioController.pinMediaOutput(deviceId, alsoPinCommunication = pinComm)
                reports += mediaReports
                // 判定以「媒体通道 + 读回验证」为准。
                // 审计 F6：早先用 any{it.ok} 汇总，媒体失败而通话成功时会误报成功。
                mediaOk = mediaReports.any { it.channel == "读回验证" && it.ok }
            } else if (pinComm) {
                val r = audioController.setCommunicationOutput(deviceId)
                reports += r
                mediaOk = r.ok
            }

            // 机器可判定的结果行（上层据此判成功，不靠 ✅ 子串）
            appendLine("RESULT_OK=$mediaOk")
            appendLine()
            reports.forEach { appendLine(it.toText()) }
            if (pinMedia && !mediaOk) {
                appendLine()
                appendLine(
                    "说明：媒体音频固定失败。可能是该系统不接受该设备作为策略首选设备" +
                        "（例如蓝牙 A2DP 需要正确的 AudioDeviceAttributes 类型/地址）。",
                )
            }
            appendLine()
            appendLine("—— 写入后实际路由 ——")
            appendLine("media: ${audioController.currentMediaOutput()?.let { "id=${it.id} type=${it.type}" } ?: "(读不到)"}")
            appendLine("comm: ${audioController.currentCommunicationOutput()}")
        })

    override fun clearAudioOutputPreference(): String = clean(buildString {
        appendLine("清除本应用设置的音频输出偏好")
        val reports = audioController.unpinMediaOutput()
        // 成功判定的唯一依据：读回验证那条报告（不再无条件返回成功 —— 审计 F7）
        val cleared = reports.any { it.channel == "读回验证" && it.ok }
        appendLine("RESULT_OK=$cleared")
        appendLine()
        reports.forEach { appendLine(it.toText()) }
        appendLine()
        appendLine("—— 清除后实际路由 ——")
        appendLine("media: ${audioController.currentMediaOutput()?.let { "id=${it.id} type=${it.type}" } ?: "(读不到)"}")
        appendLine("comm: ${audioController.currentCommunicationOutput()}")
        if (!cleared) {
            appendLine()
            appendLine("注意：仍检测到首选设备残留，系统音频状态可能未完全恢复。重启设备一定能恢复。")
        }
    })

    // ------------------------------------------------------------------
    // 外接屏显示模式
    // ------------------------------------------------------------------

    private val mirrorController: DisplayMirrorController by lazy {
        DisplayMirrorController(windowService())
    }

    override fun setExtendMode(externalDisplayId: Int): String = clean(buildString {
        appendLine("把外接屏 displayId=$externalDisplayId 切到「扩展」模式")
        appendLine("uid=${android.os.Process.myUid()}")
        // 用明确的机器可判定标记，而不是靠 ✅ 子串（信息行也会带 ✅，会污染判定）
        val result = mirrorController.applyExtendMode(externalDisplayId)
        appendLine("RESULT_OK=${result.ok}")
        appendLine()
        appendLine("信息：${result.info}")
        appendLine(result.report.toText())
        appendLine()
        appendLine(
            "读回 windowingMode = " +
                (mirrorController.getWindowingMode(externalDisplayId)?.let {
                    DisplayMirrorController.windowingModeName(it)
                } ?: "(读不到)"),
        )
    })

    override fun getDisplayModeState(externalDisplayId: Int): String =
        clean(mirrorController.describe(externalDisplayId))

    // ------------------------------------------------------------------

    override fun destroy() {
        runCatching { restoreDesktopAfterDeath() }
        ServiceContextHolder.context = null
        System.exit(0)
    }
}
