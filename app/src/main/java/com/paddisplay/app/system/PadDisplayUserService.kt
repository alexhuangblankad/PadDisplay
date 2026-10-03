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

    // ------------------------------------------------------------------
    // 系统服务 / 命令
    // ------------------------------------------------------------------

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

    override fun bindInputToDisplay(displayId: Int): String = clean(buildString {
        appendLine("=== 把外接输入设备绑定到 displayId=$displayId ===")
        val dm = injectedContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(displayId)
        if (display == null) {
            appendLine("RESULT_OK=false")
            appendLine("拿不到该 Display")
            return@buildString
        }
        val reports = inputController.bindAllExternalInputToDisplay(display)
        val ok = reports.any { it.ok && it.channel.startsWith("add") }
        appendLine("RESULT_OK=$ok")
        appendLine()
        reports.forEach { appendLine(it.toText()) }
        if (!ok) {
            appendLine()
            appendLine("说明：绑定失败时鼠标/触摸仍只能作用于默认屏。")
            appendLine("这是 Android 默认行为 —— 也正因如此，系统才用「复制模式」回避了它。")
        }
    })

    override fun clearInputAssociations(): String = clean(buildString {
        appendLine("=== 解除输入关联，恢复默认 ===")
        val reports = inputController.clearAllAssociations()
        appendLine("RESULT_OK=${reports.any { it.ok }}")
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

    override fun setDisplayTopologyLayout(
        primaryDisplayId: Int,
        otherDisplayId: Int,
        otherOnRight: Boolean,
        primaryWidth: Int,
        primaryHeight: Int,
        otherWidth: Int,
        otherHeight: Int,
    ): String = clean(buildString {
        appendLine("=== 设置多屏左右布局 ===")
        appendLine("原点显示器 displayId=$primaryDisplayId (${primaryWidth}x$primaryHeight)")
        appendLine("另一块 displayId=$otherDisplayId (${otherWidth}x$otherHeight) 放在" +
            (if (otherOnRight) "右" else "左") + "侧")
        appendLine()
        val reports = topologyController.setHorizontalLayout(
            primaryDisplayId = primaryDisplayId,
            otherDisplayId = otherDisplayId,
            otherOnRight = otherOnRight,
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
        ServiceContextHolder.context = null
        System.exit(0)
    }
}
