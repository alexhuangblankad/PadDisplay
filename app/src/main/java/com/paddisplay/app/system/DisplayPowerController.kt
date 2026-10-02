package com.paddisplay.app.system

import android.os.Build
import android.os.IBinder
import com.paddisplay.app.display.DisplayDetector

/**
 * 单独控制某块屏幕电源状态的兼容层（任务书第 7 节）。
 *
 * ## 通道清单与优先级
 *
 * | # | 通道 | 适用 | 说明 |
 * |---|---|---|---|
 * | 1 | `SurfaceControl.setDisplayPowerMode(token, mode)` | 全部 | 需要**物理屏 token**；无 Java 层权限检查，门在 SurfaceFlinger |
 * | 2 | `IDisplayManager.requestDisplayPower(displayId, state)` | API 35+ | 精确 per-display，需 `MANAGE_DISPLAYS`（shell 持有） |
 * | 3 | `PowerManager.wakeUp()`（反射） | 仅内屏恢复 | 最后兜底，防止用户永久黑屏 |
 *
 * 依次尝试，任一条成功即停止。
 *
 * ## 关键修正记录
 *
 * ### transaction code
 * `requestDisplayPower` 的 code 在 API 34/35/36 中是 **58**
 * （API 35 签名 `(int, boolean)`，API 36 `(int, int)`，code 不变）。
 * 早先版本写成 59，实际会打到 API 36 的 `requestDisplayModes(IBinder,int,int[])`，
 * 带着错位参数执行并且「成功返回」——静默什么都不做。
 *
 * ### API 36 的状态值不是 POWER_MODE
 * `SurfaceControl.POWER_MODE_*` 与 `Display.STATE_*` 是两套不同的枚举：
 * ```
 * SurfaceControl: OFF=0 DOZE=1 NORMAL=2 DOZE_SUSPEND=3 ON_SUSPEND=4
 * Display.STATE_ : UNKNOWN=0 OFF=1 ON=2 DOZE=3 DOZE_SUSPEND=4 ON_SUSPEND=6
 * ```
 * `DisplayManagerService.requestDisplayPower()` 里 `STATE_UNKNOWN(0)` 被解释为
 * 「保持当前状态」，所以给 int 版通道传 0 **不会关屏**。必须做映射。
 *
 * ### 物理屏 token 在 Android 14+ 变了位置
 * `SurfaceControl.getPhysicalDisplayIds()` / `getPhysicalDisplayToken()` /
 * `getInternalDisplayToken()` 在 Android 14 起从 `android.view.SurfaceControl`
 * 移到了 `com.android.server.display.DisplayControl`（在 services.jar 里，
 * 依赖本地库 `android_servers`）。所以这里会先试 SurfaceControl，
 * 失败再按 DisplayToggleExtreme 的做法加载 DisplayControl。
 *
 * ### 不再用 goToSleep
 * `PowerManager.goToSleep()` 是**整机级**操作：它会把包括外接屏在内的所有屏幕关掉，
 * 直接违背「只关内屏、外屏继续工作」的目标。这里只保留 `wakeUp()` 作为恢复兜底。
 */
class DisplayPowerController(
    private val displayManagerBinder: IBinder? = null,
    /** 仅内屏兜底通道用得到；Shizuku 的 UserService 会注入 Context。 */
    private val contextProvider: () -> android.content.Context? = { ServiceContextHolder.context },
) {

    companion object {
        private const val SURFACE_CONTROL = "android.view.SurfaceControl"

        // SurfaceControl.setDisplayPowerMode 的参数
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_DOZE = 1
        const val POWER_MODE_NORMAL = 2
        const val POWER_MODE_DOZE_SUSPEND = 3
        const val POWER_MODE_ON_SUSPEND = 4

        // Display.STATE_*（requestDisplayPower 的 int 版用这套）
        private const val STATE_UNKNOWN = 0
        private const val STATE_OFF = 1
        private const val STATE_ON = 2
        private const val STATE_DOZE = 3
        private const val STATE_DOZE_SUSPEND = 4
        private const val STATE_ON_SUSPEND = 6

        /** IDisplayManager.requestDisplayPower：API 35/36 都是 58。 */
        private const val REQUEST_DISPLAY_POWER_CODE = 58

        fun powerModeName(mode: Int): String = when (mode) {
            POWER_MODE_OFF -> "OFF(0)"
            POWER_MODE_DOZE -> "DOZE(1)"
            POWER_MODE_NORMAL -> "NORMAL(2)"
            POWER_MODE_DOZE_SUSPEND -> "DOZE_SUSPEND(3)"
            POWER_MODE_ON_SUSPEND -> "ON_SUSPEND(4)"
            else -> "MODE($mode)"
        }

        /** SurfaceControl 的 power mode -> Display.STATE_* */
        private fun toDisplayState(powerMode: Int): Int = when (powerMode) {
            POWER_MODE_OFF -> STATE_OFF
            POWER_MODE_DOZE -> STATE_DOZE
            POWER_MODE_NORMAL -> STATE_ON
            POWER_MODE_DOZE_SUSPEND -> STATE_DOZE_SUSPEND
            POWER_MODE_ON_SUSPEND -> STATE_ON_SUSPEND
            else -> STATE_UNKNOWN
        }
    }

    data class Report(val ok: Boolean, val channel: String, val detail: String) {
        fun toText(): String = if (ok) "✅ $channel: $detail" else "❌ $channel: $detail"
    }

    // ------------------------------------------------------------------
    // token 解析
    // ------------------------------------------------------------------

    /** 目标屏的物理 token + 人类可读标签。 */
    private fun resolveToken(displayId: Int): Pair<IBinder?, String> {
        val isInternal = isInternalDisplay(displayId)
        val token = if (isInternal) {
            internalDisplayToken()
        } else {
            physicalIdOfDisplay(displayId)?.let { physicalDisplayToken(it) }
        }
        val label = if (isInternal) {
            "内屏(displayId=$displayId)"
        } else {
            "外屏(displayId=$displayId)"
        }
        return token to label
    }

    fun isInternalDisplay(displayId: Int): Boolean {
        if (displayId == android.view.Display.DEFAULT_DISPLAY) return true
        val display = contextDisplay(displayId) ?: return false
        val type = Reflect.call(display, "getType").getOrNull() as? Int
        if (type == DisplayDetector.DisplayType.INTERNAL) return true
        val address = Reflect.call(display, "getAddress").getOrNull() as? String
        return address != null && address.startsWith("local:") &&
            DisplayDetector.localPort(address) == 0
    }

    private fun contextDisplay(displayId: Int): android.view.Display? = try {
        val ctx = contextProvider()
        val dm = ctx?.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager
        dm?.getDisplay(displayId)
    } catch (t: Throwable) {
        null
    }

    /** 逻辑 displayId -> 物理 display id（从隐藏的 `Display.getAddress()` 解析）。 */
    private fun physicalIdOfDisplay(displayId: Int): Long? {
        val display = contextDisplay(displayId) ?: return null
        val address = Reflect.call(display, "getAddress").getOrNull() as? String
        return DisplayDetector.physicalIdOf(address)
    }


    // ------------------------------------------------------------------
    // 物理屏 token：统一走 PhysicalDisplayAccess（含 DisplayControl 兜底）
    // ------------------------------------------------------------------

    private fun internalDisplayToken(): IBinder? = PhysicalDisplayAccess.internalDisplayToken()

    fun physicalDisplayIds(): LongArray? = PhysicalDisplayAccess.physicalDisplayIds()

    fun physicalDisplayToken(physicalId: Long): IBinder? =
        PhysicalDisplayAccess.physicalDisplayToken(physicalId)

    // ------------------------------------------------------------------
    // 设置电源
    // ------------------------------------------------------------------

    /** 设置某块屏幕的电源模式，返回每一次尝试的完整报告。 */
    fun setPower(displayId: Int, powerMode: Int): List<Report> {
        val reports = mutableListOf<Report>()
        val isInternal = isInternalDisplay(displayId)
        val (token, label) = resolveToken(displayId)

        // --- 通道 1: SurfaceControl.setDisplayPowerMode ---
        if (token != null) {
            reports += trySetDisplayPowerMode(token, powerMode, label)
        } else {
            reports += Report(
                false,
                "SurfaceControl.setDisplayPowerMode",
                "$label 拿不到物理屏 token（SurfaceControl 与 DisplayControl 都失败）",
            )
        }
        if (reports.any { it.ok }) return reports

        // --- 通道 2: IDisplayManager.requestDisplayPower（API 35+） ---
        reports += tryRequestDisplayPower(displayId, powerMode, label)
        if (reports.any { it.ok }) return reports

        // --- 通道 3: 仅内屏的恢复兜底（绝不 goToSleep，那是整机关屏） ---
        if (isInternal && powerMode == POWER_MODE_NORMAL) {
            reports += tryWakeUp()
        }

        return reports
    }

    private fun trySetDisplayPowerMode(token: IBinder, powerMode: Int, label: String): Report {
        val channel = "SurfaceControl.setDisplayPowerMode"
        val clazz = Reflect.classForName(SURFACE_CONTROL)
            ?: return Report(false, channel, "找不到 android.view.SurfaceControl")
        val method = Reflect.findMethod(
            clazz,
            "setDisplayPowerMode",
            IBinder::class.java,
            Int::class.javaPrimitiveType,
        ) ?: return Report(false, channel, "方法不存在（ROM 可能已移除）")

        return try {
            method.invoke(null, token, powerMode)
            Report(true, channel, "$label -> ${powerModeName(powerMode)}")
        } catch (t: Throwable) {
            Report(false, channel, "$label 失败 ${Reflect.describe(t)}")
        }
    }

    private fun tryRequestDisplayPower(displayId: Int, powerMode: Int, label: String): Report {
        val channel = "IDisplayManager.requestDisplayPower"
        if (Build.VERSION.SDK_INT < 35) {
            return Report(false, channel, "API ${Build.VERSION.SDK_INT} < 35，该通道不存在")
        }
        val binder = displayManagerBinder
            ?: return Report(false, channel, "取不到 display 系统服务")

        // API 35 是 (int, boolean)；API 36 是 (int, int state)
        val asBoolean = Build.VERSION.SDK_INT < 36
        val on = powerMode == POWER_MODE_NORMAL
        val state = toDisplayState(powerMode)

        return AidlCodec.call(
            binder = binder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "$channel($displayId, " + (if (asBoolean) "on=$on" else "state=$state") + ")",
            code = REQUEST_DISPLAY_POWER_CODE,
            writeArgs = { data ->
                data.writeInt(displayId)
                // 两种签名在 wire 上都是先 int 后 int（boolean 用 0/1 编码）
                data.writeInt(if (asBoolean) (if (on) 1 else 0) else state)
            },
            readReply = { it.readInt() != 0 },
        ).fold(
            onSuccess = { accepted ->
                if (accepted) {
                    Report(true, channel, "$label -> " + (if (asBoolean) "on=$on" else "state=$state"))
                } else {
                    Report(false, channel, "$label 服务端拒绝了该请求（返回 false）")
                }
            },
            onFailure = { Report(false, channel, "$label 失败 ${Reflect.describe(it)}") },
        )
    }

    /**
     * 仅用于「点亮内屏」的兜底。
     * `PowerManager.wakeUp()` 不在公共 SDK 里，只能反射；需要 `DEVICE_POWER`（shell 持有）。
     */
    private fun tryWakeUp(): Report {
        val channel = "PowerManager.wakeUp(反射)"
        return try {
            val context = contextProvider()
                ?: return Report(false, channel, "没有 Context，无法使用兜底通道")
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE)
                ?: return Report(false, channel, "取不到 PowerManager")
            val m = Reflect.findMethod(pm.javaClass, "wakeUp", Long::class.javaPrimitiveType)
                ?: return Report(false, channel, "PowerManager.wakeUp 不存在")
            m.invoke(pm, System.currentTimeMillis())
            Report(true, channel, "wakeUp() 唤醒（兜底；注意这是整机唤醒，不是 per-display）")
        } catch (t: Throwable) {
            Report(false, channel, "兜底失败 ${Reflect.describe(t)}")
        }
    }

    // ------------------------------------------------------------------
    // 状态读取
    // ------------------------------------------------------------------

    fun readPowerState(displayId: Int): String {
        val display = contextDisplay(displayId)
            ?: return "displayId=$displayId 无法读取（可能已被系统移出枚举）"
        val type = Reflect.call(display, "getType").getOrNull()
        val address = Reflect.call(display, "getAddress").getOrNull()
        val mode = runCatching { display.mode }.getOrNull()
        return "state=${display.state} type=$type addr=$address " +
            "mode=" + (mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" } ?: "?")
    }

    /** 诊断：把各通道可用性汇总。 */
    fun describeChannels(): String = buildString {
        appendLine(PhysicalDisplayAccess.describe())
        appendLine("display binder: ${AidlCodec.describeBinder(displayManagerBinder)}")
        appendLine("requestDisplayPower code: $REQUEST_DISPLAY_POWER_CODE (API 35/36 一致)")
        appendLine(
            "requestDisplayPower 签名: " +
                if (Build.VERSION.SDK_INT >= 36) "(int displayId, int state)" else "(int displayId, boolean on)",
        )
    }
}

/**
 * UserService 进程里没有 Activity，需要一个 Context 才能拿 PowerManager。
 * Shizuku 会在构造 UserService 时注入 Context，这里保存下来给工具类用。
 */
object ServiceContextHolder {
    @Volatile
    var context: android.content.Context? = null

    /**
     * 从系统服务 Manager 对象里取底层 IBinder。
     *
     * ⚠️ 这只是**兜底**：不同 ROM 的 Manager 字段名不一样
     * （例如 Android 16 的 `WindowManagerImpl` 里根本没有 IWindowManager 字段，
     * 真正的服务是静态的 `WindowManagerGlobal.sWindowManagerService`）。
     * 主路径始终是 `ServiceManager.getService(name)`。
     */
    fun getSystemServiceBinder(name: String): IBinder? = try {
        val svc = context?.getSystemService(name) ?: return null
        extractBinder(svc, 0)
    } catch (t: Throwable) {
        null
    }

    private val knownFields = listOf(
        "mService", "mDm", "mGlobal", "mDisplayManager", "mWm", "mWindowManager",
    )

    private fun extractBinder(service: Any, depth: Int): IBinder? {
        if (service is IBinder) return service
        if (depth > 2) return null

        // 1) 已知字段名
        for (field in knownFields) {
            val f = runCatching {
                service.javaClass.getDeclaredField(field).also { it.isAccessible = true }
            }.getOrNull() ?: continue
            val v = runCatching { f.get(service) }.getOrNull() ?: continue
            if (v is IBinder) return v
            extractBinder(v, depth + 1)?.let { return it }
        }

        // 2) WindowManagerImpl 的情况：真正的服务在静态字段里
        Reflect.classForName("android.view.WindowManagerGlobal")?.let { wmg ->
            Reflect.getStaticField(wmg, "sWindowManagerService").getOrNull()?.let {
                if (it is IBinder) return it
            }
        }
        return null
    }
}
