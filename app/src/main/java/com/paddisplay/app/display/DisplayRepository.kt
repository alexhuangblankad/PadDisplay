package com.paddisplay.app.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import com.paddisplay.app.system.PhysicalDisplayAccess
import com.paddisplay.app.system.Reflect
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 0 的核心：枚举所有 Display、读取当前 Mode 与 supportedModes。
 *
 * 只用 Android 公共 API + 少量隐藏方法反射（getType / getAddress /
 * SurfaceControl.getPhysicalDisplayId），**不需要 Shizuku 也不需要 Root**。
 * 这一步必须先能在 OPPO Pad mini 上把外接屏打印出来，才谈得上后面的控制。
 */
class DisplayRepository(private val context: Context) {

    companion object {
        private const val TAG = "PadDisplay/DisplayRepo"
        private const val SURFACE_CONTROL = "android.view.SurfaceControl"

        /** 虚拟屏类型，本项目不关心（任务书禁止做虚拟显示器/投屏）。 */
        private val IGNORED_TYPES = setOf(
            DisplayDetector.DisplayType.VIRTUAL,
            DisplayDetector.DisplayType.OVERLAY,
        )
    }

    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** 用户手动指定的屏幕角色，displayId -> role。 */
    private val userOverrides = HashMap<Int, DisplayRole>()

    /** 最近一次枚举结果，供监听器对比。 */
    @Volatile
    var lastSnapshot: List<DisplaySnapshot> = emptyList()
        private set

    private val listeners = CopyOnWriteArrayList<(List<DisplaySnapshot>) -> Unit>()

    fun setUserOverride(displayId: Int, role: DisplayRole?) {
        if (role == null) userOverrides.remove(displayId) else userOverrides[displayId] = role
    }

    fun getUserOverrides(): Map<Int, DisplayRole> = userOverrides.toMap()

    fun addListener(l: (List<DisplaySnapshot>) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (List<DisplaySnapshot>) -> Unit) {
        listeners.remove(l)
    }

    // ------------------------------------------------------------------
    // 枚举
    // ------------------------------------------------------------------

    /** 枚举当前所有显示器（已过滤虚拟屏）。 */
    fun enumerate(): List<DisplaySnapshot> {
        val rawDisplays = collectDisplays()

        // 预先取出物理屏 id 集合，用于“物理 token 是否可用”的判断
        val physicalIds = physicalDisplayIds()

        // 第一遍：找出无争议的内屏数量
        val prelim = rawDisplays.map { d ->
            val typeCode = displayType(d)
            val address = displayAddress(d)
            Triple(d, typeCode, address)
        }
        val internalCount = prelim.count { (d, typeCode, address) ->
            DisplayDetector.isClearlyInternal(d.displayId, typeCode, d.flags, address)
        }

        // 第二遍：构造快照
        val snapshots = prelim.map { (display, typeCode, address) ->
            buildSnapshot(display, typeCode, address, internalCount, physicalIds)
        }

        val sorted = snapshots.sortedWith(
            compareBy<DisplaySnapshot> { if (it.isInternal) 0 else 1 }.thenBy { it.displayId },
        )
        lastSnapshot = sorted
        sorted.forEach { s ->
            Log.i(
                TAG,
                "Display ${s.displayId} name=${s.name} role=${s.role} type=${s.typeName} " +
                    "addr=${s.address} current=${s.currentModeLabel} modes=${s.supportedModes.size} " +
                    "physicalId=${s.physicalDisplayId} token=${s.physicalTokenAvailable}",
            )
        }
        listeners.forEach { it(sorted) }
        return sorted
    }

    private fun collectDisplays(): List<Display> {
        val result = LinkedHashMap<Int, Display>()

        fun addAll(displays: Array<Display>?) {
            displays?.forEach { d ->
                if (!result.containsKey(d.displayId)) result[d.displayId] = d
            }
        }

        // 公共 API 1：默认（包含内屏 + 已启用的外接屏）
        runCatching { addAll(displayManager.getDisplays()) }
            .onFailure { Log.w(TAG, "getDisplays() 失败: ${Reflect.describe(it)}") }

        // 公共 API 2：presentation 类别，某些 ColorOS 版本用这个才拿到外接屏
        runCatching { addAll(displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) }
            .onFailure { Log.w(TAG, "getDisplays(PRESENTATION) 失败: ${Reflect.describe(it)}") }

        // 隐藏 API：可以拿到「已存在但被关闭」的屏幕
        // —— 这正好对应内屏被我们关掉之后的状态，对 failsafe 很关键。
        hiddenAllIncludingDisabledCategory()?.let { category ->
            runCatching { addAll(displayManager.getDisplays(category)) }
                .onFailure { Log.w(TAG, "getDisplays($category) 失败: ${Reflect.describe(it)}") }
        }

        return result.values
            .filter { displayType(it) !in IGNORED_TYPES }
            .toList()
    }

    /**
     * 该常量的名字在 Android 16 改过：
     * - API 32~35: `DISPLAY_CATEGORY_ALL_INCLUDES_DISABLED`
     * - API 36+  : `DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED`
     * 两个都试，避免新版上「关掉的内屏看不见」导致 failsafe 失效。
     */
    private fun hiddenAllIncludingDisabledCategory(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return null
        val clazz = Reflect.classForName("android.hardware.display.DisplayManager") ?: return null
        val names = buildList {
            if (Build.VERSION.SDK_INT >= 36) {
                add("DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED")
                add("DISPLAY_CATEGORY_ALL_INCLUDES_DISABLED")
            } else {
                add("DISPLAY_CATEGORY_ALL_INCLUDES_DISABLED")
                add("DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED")
            }
        }
        for (n in names) {
            (Reflect.getStaticField(clazz, n).getOrNull() as? String)?.let { return it }
        }
        return null
    }

    private fun buildSnapshot(
        display: Display,
        typeCode: Int,
        address: String?,
        internalCount: Int,
        physicalIds: LongArray?,
    ): DisplaySnapshot {
        val currentMode = runCatching { display.mode }.getOrNull()?.toInfo()
        val supported = runCatching { display.supportedModes.orEmpty().map { it.toInfo() } }
            .getOrElse { emptyList() }

        val preferred = preferredModeOf(display)?.toInfo()

        val logical = android.graphics.Point()
        runCatching { display.getSize(logical) }

        // Display.getMetrics() 是隐藏 API，这里用公共的 getRealSize + 反射读 densityDpi。
        // densityDpi 只用于展示，取不到就填 0。
        val densityDpi = runCatching {
            val m = Reflect.findMethod(display.javaClass, "getMetrics")
            if (m != null) {
                val metrics = android.util.DisplayMetrics()
                m.invoke(display, metrics)
                metrics.densityDpi
            } else {
                0
            }
        }.getOrDefault(0)

        val physicalId = DisplayDetector.physicalIdOf(address)
        val tokenAvailable = physicalId != null &&
            physicalIds != null &&
            physicalIds.contains(physicalId) &&
            physicalDisplayToken(physicalId) != null

        val (role, evidence) = DisplayDetector.detect(
            displayId = display.displayId,
            typeCode = typeCode,
            flags = display.flags,
            address = address,
            internalCount = internalCount,
            userOverride = userOverrides,
        )

        return DisplaySnapshot(
            displayId = display.displayId,
            name = display.name ?: "Display ${display.displayId}",
            typeCode = typeCode,
            typeName = typeNameOf(typeCode),
            flags = display.flags,
            flagNames = flagNamesOf(display.flags),
            stateCode = display.state,
            stateName = stateNameOf(display.state),
            rotation = runCatching { display.rotation }.getOrDefault(0),
            address = address,
            logicalWidth = logical.x,
            logicalHeight = logical.y,
            logicalDensityDpi = densityDpi,
            currentMode = currentMode,
            supportedModes = supported,
            preferredMode = preferred,
            isDefaultDisplay = display.displayId == Display.DEFAULT_DISPLAY,
            role = role,
            evidence = evidence,
            physicalDisplayId = physicalId,
            physicalTokenAvailable = tokenAvailable,
        )
    }

    // ------------------------------------------------------------------
    // 隐藏 API 访问
    // ------------------------------------------------------------------

    private fun displayType(display: Display): Int =
        Reflect.call(display, "getType").getOrNull() as? Int ?: DisplayDetector.DisplayType.UNKNOWN

    private fun displayAddress(display: Display): String? =
        Reflect.call(display, "getAddress").getOrNull() as? String

    /** IDisplayManager.getPreferredDisplayMode 未公开，走 DisplayManagerGlobal 反射。 */
    private fun preferredModeOf(display: Display): Display.Mode? {
        val global = runCatching {
            val clazz = Reflect.classForName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = Reflect.findMethod(clazz, "getInstance")
            getInstance?.invoke(null)
        }.getOrNull() ?: return null

        return runCatching {
            val m = Reflect.findMethod(
                global.javaClass,
                "getPreferredDisplayMode",
                Int::class.javaPrimitiveType,
            ) ?: return null
            m.invoke(global, display.displayId) as? Display.Mode
        }.getOrNull()
    }

    /**
     * 物理屏相关调用统一委托给 [PhysicalDisplayAccess]：
     * Android 14+ 上这些方法已从 SurfaceControl 搬到
     * `com.android.server.display.DisplayControl`，那里做了兜底。
     */
    private fun physicalDisplayIds(): LongArray? = PhysicalDisplayAccess.physicalDisplayIds()

    fun physicalDisplayToken(physicalId: Long): IBinder? =
        PhysicalDisplayAccess.physicalDisplayToken(physicalId)

    /** 内屏的物理 token（关/开内屏时用，避免依赖 displayId 约定）。 */
    fun internalDisplayToken(): IBinder? = PhysicalDisplayAccess.internalDisplayToken()

    // ------------------------------------------------------------------
    // 便利查询
    // ------------------------------------------------------------------

    fun internalDisplay(): DisplaySnapshot? = lastSnapshot.firstOrNull { it.isInternal }

    fun externalDisplays(): List<DisplaySnapshot> = lastSnapshot.filter { it.isExternal }

    fun primaryExternalDisplay(): DisplaySnapshot? = externalDisplays().firstOrNull()

    fun byId(displayId: Int): DisplaySnapshot? = lastSnapshot.firstOrNull { it.displayId == displayId }

    /** 供 DisplayPowerController 使用：拿 displayId 对应的物理 token。 */
    fun tokenFor(displayId: Int): IBinder? {
        val snap = byId(displayId) ?: return null
        if (snap.isInternal) return internalDisplayToken()
        return snap.physicalDisplayId?.let { physicalDisplayToken(it) }
    }

    private fun Display.Mode.toInfo(): DisplayModeInfo {
        val alternative = runCatching {
            val m = Reflect.findMethod(javaClass, "getAlternativeRefreshRates")
            val rates = m?.invoke(this) as? FloatArray
            rates != null && rates.isNotEmpty()
        }.getOrDefault(false)

        return DisplayModeInfo(
            modeId = this.modeId,
            physicalWidth = this.physicalWidth,
            physicalHeight = this.physicalHeight,
            refreshRate = this.refreshRate,
            isAlternative = alternative,
        )
    }

    private fun typeNameOf(type: Int): String = DisplayDetector.DisplayType.name(type)

    private fun stateNameOf(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF(已关闭)"
        Display.STATE_ON -> "ON(开启)"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_UNKNOWN -> "UNKNOWN"
        Display.STATE_VR -> "VR"
        else -> "STATE($state)"
    }

    private fun flagNamesOf(flags: Int): List<String> {
        val out = mutableListOf<String>()
        if (flags and Display.FLAG_SECURE != 0) out += "SECURE"
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) out += "PROTECTED_BUFFERS"
        if (flags and Display.FLAG_PRIVATE != 0) out += "PRIVATE"
        if (flags and Display.FLAG_PRESENTATION != 0) out += "PRESENTATION"
        if (flags and Display.FLAG_ROUND != 0) out += "ROUND"
        if (out.isEmpty()) out += "NONE"
        return out
    }
}
