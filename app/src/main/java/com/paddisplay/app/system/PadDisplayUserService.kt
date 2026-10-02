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

    override fun execCommand(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
            }
            process.waitFor()
            val out = sb.toString()
            if (out.length > 200_000) out.substring(0, 200_000) + "\n...(输出已截断)" else out
        } catch (t: Throwable) {
            "execCommand 失败: ${Reflect.describe(t)}"
        }
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    override fun collectSystemInfo(): String = buildString {
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
    }

    override fun probeCapabilities(): String = buildString {
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
    }

    override fun dumpDisplay(): String = execCommand("dumpsys display")

    override fun listPhysicalDisplays(): String = buildString {
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
    }

    // ------------------------------------------------------------------
    // 电源
    // ------------------------------------------------------------------

    override fun setDisplayPowerMode(displayId: Int, powerMode: Int): String {
        val reports = powerController.setPower(displayId, powerMode)
        return buildString {
            appendLine("displayId=$displayId 请求电源模式 ${DisplayPowerController.powerModeName(powerMode)}")
            reports.forEach { appendLine(it.toText()) }
            val ok = reports.firstOrNull { it.ok }
            appendLine()
            appendLine(if (ok != null) "结果: 成功（通道 ${ok.channel}）" else "结果: 全部通道失败")
            if (ok == null) appendLine("Uid=${android.os.Process.myUid()}，请把以上完整信息发回排查")
        }
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
        return buildString {
            appendLine("displayId=$displayId 逻辑尺寸覆盖 ${width}x$height")
            appendLine("修改前: ${before.text}")
            reports.forEach { appendLine(it.toText()) }
            appendLine("修改后: ${after.text}")
            appendLine()
            appendLine("提示: 这是逻辑尺寸覆盖（不改硬件时序）。要切真实 Mode 请用 setUserPreferredDisplayMode。")
        }
    }

    override fun clearForcedDisplaySize(displayId: Int): String {
        val reports = resolutionController.clearForcedDisplaySize(displayId)
        val after = resolutionController.readDisplaySize(displayId)
        return buildString {
            appendLine("displayId=$displayId 清除逻辑尺寸覆盖")
            reports.forEach { appendLine(it.toText()) }
            appendLine("清除后: ${after.text}")
        }
    }

    override fun getDisplaySizes(displayId: Int): String = buildString {
        appendLine("displayId=$displayId")
        appendLine("wm size -d $displayId:")
        appendLine(resolutionController.readDisplaySize(displayId).text)
        appendLine()
        appendLine("getBaseDisplaySize (IWindowManager): ${resolutionController.readBaseDisplaySize(displayId) ?: "(读取失败)"}")
        appendLine("getInitialDisplaySize (IWindowManager): ${resolutionController.readInitialDisplaySize(displayId) ?: "(读取失败)"}")
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
        return buildString {
            appendLine("displayId=$displayId 请求切换 Mode -> ${info.label} (modeId=$modeId)")
            reports.forEach { appendLine(it.toText()) }
            appendLine()
            appendLine(if (reports.any { it.ok }) "结果: 成功" else "结果: 失败（可回退到逻辑尺寸覆盖通道）")
        }
    }

    override fun resetUserPreferredDisplayMode(displayId: Int): String =
        resolutionController.resetUserPreferredMode(displayId).toText()

    // ------------------------------------------------------------------

    override fun destroy() {
        ServiceContextHolder.context = null
        System.exit(0)
    }
}
