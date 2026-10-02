package com.paddisplay.app.system

import android.content.Context
import android.os.IBinder
import android.util.Log
import com.paddisplay.app.data.SettingsRepository
import com.paddisplay.app.display.DisplayModeInfo
import com.paddisplay.app.display.DisplayModeSelector
import com.paddisplay.app.display.DisplayRepository
import com.paddisplay.app.display.DisplaySnapshot
import com.paddisplay.app.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 业务门面：把「App 侧公共 API 枚举」与「UserService 侧特权调用」拼在一起。
 *
 * 分工刻意分得很清楚：
 *  - **枚举 / 读取 Mode**：App 侧自己做（公共 API 就够，不用惊动 Shizuku，
 *    这样即使 Shizuku 没启动，Phase 0 的探针功能依然可用）。
 *  - **改分辨率 / 开关屏**：必须走 Shizuku UserService（shell 权限）。
 */
class SystemDisplayService(
    private val context: Context,
    private val settings: SettingsRepository = SettingsRepository(context),
) {

    companion object {
        private const val TAG = "PadDisplay/SystemSvc"

        /** 关屏 / 开屏用的 power mode。 */
        const val POWER_OFF = 0
        const val POWER_NORMAL = 2
    }

    val repo = DisplayRepository(context)

    /** 统一的操作结果。 */
    data class OpResult(
        val ok: Boolean,
        val title: String,
        val lines: List<String> = emptyList(),
    ) {
        fun toText(): String = buildString {
            append(if (ok) "✅ " else "❌ ")
            append(title)
            if (lines.isNotEmpty()) {
                append('\n')
                lines.forEach { append("  ").append(it).append('\n') }
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // Phase 0：枚举（不依赖 Shizuku）
    // ------------------------------------------------------------------

    fun enumerateDisplays(roleOverrides: Map<Int, com.paddisplay.app.display.DisplayRole> = emptyMap()): List<DisplaySnapshot> {
        roleOverrides.forEach { (id, role) -> repo.setUserOverride(id, role) }
        return repo.enumerate()
    }

    fun internalDisplay(): DisplaySnapshot? = repo.internalDisplay()

    fun externalDisplays(): List<DisplaySnapshot> = repo.externalDisplays()

    fun primaryExternal(): DisplaySnapshot? = repo.primaryExternalDisplay()

    // ------------------------------------------------------------------
    // 特权调用：统一走 Shizuku
    // ------------------------------------------------------------------

    private fun service(): com.paddisplay.app.IPadDisplayService? = ShizukuManager.service

    private suspend fun requireService(): Pair<com.paddisplay.app.IPadDisplayService?, OpResult?> {
        val svc = service()
        if (svc == null) {
            return null to OpResult(
                ok = false,
                title = "系统控制权限未就绪",
                lines = listOf(
                    "当前状态：${ShizukuManager.state.value.message}",
                    "请先完成 Shizuku 授权（不需要 Root）。",
                ),
            )
        }
        return svc to null
    }

    suspend fun collectSystemInfo(): String = withContext(Dispatchers.IO) {
        service()?.let { runCatching { it.collectSystemInfo() }.getOrElse { t -> "调用失败: ${Reflect.describe(t)}" } }
            ?: "UserService 未连接"
    }

    suspend fun probeCapabilities(): String = withContext(Dispatchers.IO) {
        service()?.let { runCatching { it.probeCapabilities() }.getOrElse { t -> "调用失败: ${Reflect.describe(t)}" } }
            ?: "UserService 未连接，无法探测"
    }

    suspend fun dumpDisplay(): String = withContext(Dispatchers.IO) {
        service()?.let { runCatching { it.dumpDisplay() }.getOrElse { t -> "调用失败: ${Reflect.describe(t)}" } }
            ?: "UserService 未连接"
    }

    suspend fun listPhysicalDisplays(): String = withContext(Dispatchers.IO) {
        service()?.let { runCatching { it.listPhysicalDisplays() }.getOrElse { t -> "调用失败: ${Reflect.describe(t)}" } }
            ?: "UserService 未连接"
    }

    suspend fun execCommand(command: String): String = withContext(Dispatchers.IO) {
        service()?.let { runCatching { it.execCommand(command) }.getOrElse { t -> "调用失败: ${Reflect.describe(t)}" } }
            ?: "UserService 未连接"
    }

    // ------------------------------------------------------------------
    // Phase 2：单独关屏 / 开屏
    // ------------------------------------------------------------------

    /**
     * 找到内屏的 displayId。
     *
     * ⚠️ 关键点：内屏被关掉之后，系统**可能把它从 Display 枚举里移除**，
     * 此时 `repo.internalDisplay()` 返回 null。若直接放弃，用户就会永久黑屏。
     * 所以这里有多级回退：
     * 1. 枚举结果里判定为内屏的
     * 2. 曾经记录过的内屏 displayId（DataStore 持久化）
     * 3. `Display.DEFAULT_DISPLAY`（AOSP 保证它是默认屏）
     */
    suspend fun resolveInternalDisplayId(): Int {
        repo.internalDisplay()?.let { return it.displayId }
        val remembered = runCatching { settings.lastKnownInternalDisplay.first() }.getOrNull()
        if (remembered != null && remembered >= 0) return remembered
        return android.view.Display.DEFAULT_DISPLAY
    }

    /** 关闭内屏。这是项目的两个核心实验之一。 */
    suspend fun turnOffInternalDisplay(): OpResult {
        val id = resolveInternalDisplayId()
        val viaRepo = repo.internalDisplay() != null
        if (!viaRepo) {
            appendNote("内屏不在枚举结果中，回退使用 displayId=$id 尝试关闭")
        }
        return setDisplayPower(id, POWER_OFF)
    }

    /** 打开内屏。failsafe 会反复调用它，所以必须尽量总能成功。 */
    suspend fun turnOnInternalDisplay(): OpResult {
        val id = resolveInternalDisplayId()
        val viaRepo = repo.internalDisplay() != null
        if (!viaRepo) {
            appendNote("内屏不在枚举结果中，回退使用 displayId=$id 尝试点亮")
        }
        return setDisplayPower(id, POWER_NORMAL)
    }

    /**
     * 带重试的内屏恢复 —— failsafe 专用。
     *
     * 拔掉外接屏的瞬间，DisplayManager 的状态可能还没稳定，
     * 单次调用容易失败。这里最多尝试 [attempts] 次，并逐次加大间隔。
     */
    suspend fun restoreInternalDisplayWithRetry(attempts: Int = 4): OpResult {
        var last: OpResult = OpResult(false, "未执行", emptyList())
        for (i in 1..attempts) {
            last = turnOnInternalDisplay()
            if (last.ok) {
                if (i > 1) appendNote("内屏恢复在第 $i 次尝试后成功")
                return last
            }
            appendNote("内屏恢复第 $i 次失败，${400L * i}ms 后重试")
            kotlinx.coroutines.delay(400L * i)
        }
        return last
    }

    private fun appendNote(msg: String) {
        Log.w(TAG, msg)
    }

    // ------------------------------------------------------------------
    // 内屏关闭状态的持久化（failsafe 的依据）
    // ------------------------------------------------------------------

    /**
     * 记录「内屏是否被我们关掉了」，并解析出内屏 displayId 一并持久化。
     * App 被系统杀掉后重启时会读这个状态，自动点亮内屏。
     */
    suspend fun markInternalOff(off: Boolean) {
        runCatching {
            settings.setInternalScreenOff(off)
            if (!off) {
                // 记录内屏 id，供下次「枚举里找不到内屏」时回退使用
                repo.internalDisplay()?.let { settings.setLastKnownInternalDisplay(it.displayId) }
            }
        }
    }

    /** 读取上次是否把内屏关掉了。 */
    suspend fun wasInternalScreenOff(): Boolean =
        runCatching { settings.internalScreenOff.first() }.getOrDefault(false)

    /**
     * 单独设置某块屏的电源。
     *
     * 关键点：**一次只操作一个 displayId**。
     * 关内屏时只传内屏 id，外屏完全不受影响 —— 这正是
     * DisplayToggleExtreme 里「跳过指定 display id」思路的等价实现。
     */
    suspend fun setDisplayPower(displayId: Int, powerMode: Int): OpResult = withContext(Dispatchers.IO) {
        val (svc, err) = requireService()
        if (svc == null) return@withContext err!!

        val snapshot = repo.byId(displayId)
        val roleText = snapshot?.let { "${it.typeLabel}「${it.name}」(displayId=$displayId)" }
            ?: "displayId=$displayId"

        runCatching {
            val out = svc.setDisplayPowerMode(displayId, powerMode)
            // UserService 在任一通道成功时会输出「结果: 成功（通道 …）」
            val ok = out.contains("结果: 成功")
            OpResult(
                ok = ok,
                title = (if (powerMode == POWER_OFF) "关闭 " else "打开 ") + roleText,
                lines = out.lines().filter { it.isNotBlank() },
            )
        }.getOrElse { t ->
            OpResult(false, "调用 UserService 失败", listOf(Reflect.describe(t)))
        }
    }

    // ------------------------------------------------------------------
    // Phase 3：外屏 Mode / 分辨率
    // ------------------------------------------------------------------

    /**
     * 把外接屏切到指定 Mode。
     *
     * 优先真实 Mode 切换（`IDisplayManager.setUserPreferredDisplayMode`），
     * 失败则按设置回退到 per-display 逻辑尺寸覆盖（`IWindowManager.setForcedDisplaySize`）。
     */
    suspend fun applyExternalMode(
        displayId: Int,
        mode: DisplayModeInfo,
        allowForcedSizeFallback: Boolean,
    ): OpResult = withContext(Dispatchers.IO) {
        val (svc, err) = requireService()
        if (svc == null) return@withContext err!!

        val lines = mutableListOf<String>()

        // --- 通道 A：真实 Mode ---
        val modeOut = runCatching {
            svc.setUserPreferredDisplayMode(
                displayId,
                mode.modeId,
                mode.physicalWidth,
                mode.physicalHeight,
                mode.refreshRate,
            )
        }.getOrElse { "调用失败: ${Reflect.describe(it)}" }

        lines += "— 真实 Mode 切换 —"
        lines += modeOut.lines().filter { it.isNotBlank() }
        val modeOk = modeOut.contains("结果: 成功")

        if (modeOk) {
            return@withContext OpResult(true, "外接屏已切到 ${mode.label}", lines)
        }

        // --- 通道 B：per-display 逻辑尺寸覆盖 ---
        if (allowForcedSizeFallback) {
            lines += ""
            lines += "— 回退：per-display 逻辑尺寸覆盖 —"
            val sizeOut = runCatching {
                svc.setForcedDisplaySize(displayId, mode.physicalWidth, mode.physicalHeight)
            }.getOrElse { "调用失败: ${Reflect.describe(it)}" }
            lines += sizeOut.lines().filter { it.isNotBlank() }
            // 只有 UserService 明确报告成功才算成功，不能因为文本里含「修改后」就判定成功
            val sizeOk = sizeOut.contains("✅")
            return@withContext OpResult(
                ok = sizeOk,
                title = if (sizeOk) {
                    "外接屏已通过逻辑尺寸覆盖设为 ${mode.physicalWidth}×${mode.physicalHeight}"
                } else {
                    "逻辑尺寸覆盖也失败了"
                },
                lines = lines,
            )
        }

        OpResult(false, "真实 Mode 切换失败，且未启用回退通道", lines)
    }

    /** 使用“最佳分辨率”（外屏最高分辨率 + 默认 60 Hz）。 */
    suspend fun applyBestExternalMode(allowForcedSizeFallback: Boolean): OpResult {
        val external = repo.primaryExternalDisplay()
            ?: return OpResult(false, "未检测到外接显示器", listOf("请先通过 USB-C / DisplayPort Alt Mode 连接显示器"))
        if (external.supportedModes.isEmpty()) {
            return OpResult(
                false,
                "外接屏未上报任何 supportedModes",
                listOf("显示器名：${external.name}", "无法自动选择分辨率，请手动指定。"),
            )
        }
        val best = DisplayModeSelector.selectBest(external)
            ?: return OpResult(false, "无法选出最佳 Mode", emptyList())
        return applyExternalMode(external.displayId, best, allowForcedSizeFallback)
    }

    suspend fun clearExternalForcedSize(displayId: Int): OpResult = withContext(Dispatchers.IO) {
        val (svc, err) = requireService()
        if (svc == null) return@withContext err!!
        runCatching {
            val out = svc.clearForcedDisplaySize(displayId)
            val ok = out.contains("✅")
            OpResult(ok, if (ok) "已清除 displayId=$displayId 的逻辑尺寸覆盖" else "清除失败", out.lines().filter { it.isNotBlank() })
        }.getOrElse { OpResult(false, "清除失败", listOf(Reflect.describe(it))) }
    }

    /**
     * 记录当前外屏逻辑尺寸，用于防黑屏回滚。
     * `wm size -d <id>` 的真实输出是 `Physical size: WxH` / `Override size: WxH`。
     * 返回 "WxH"，取不到则返回 null。
     */
    suspend fun captureExternalSize(displayId: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val svc = service() ?: return@withContext null
        val out = runCatching { svc.getDisplaySizes(displayId) }.getOrNull() ?: return@withContext null
        // 有 Override 说明被强制改过，回滚目标是 Override；否则是 Physical
        val override = Regex("""Override size:\s*(\d+)x(\d+)""").find(out)
        val physical = Regex("""Physical size:\s*(\d+)x(\d+)""").find(out)
        val m = override ?: physical ?: return@withContext null
        val w = m.groupValues[1].toIntOrNull() ?: return@withContext null
        val h = m.groupValues[2].toIntOrNull() ?: return@withContext null
        w to h
    }

    /** 回滚到指定逻辑尺寸。 */
    suspend fun restoreExternalSize(displayId: Int, width: Int, height: Int): OpResult =
        withContext(Dispatchers.IO) {
            val (svc, err) = requireService()
            if (svc == null) return@withContext err!!
            runCatching {
                val out = svc.setForcedDisplaySize(displayId, width, height)
                val ok = out.contains("✅")
                OpResult(
                    ok,
                    if (ok) "已回滚 displayId=$displayId 到 ${width}x$height" else "回滚失败",
                    out.lines().filter { it.isNotBlank() },
                )
            }.getOrElse { OpResult(false, "回滚失败", listOf(Reflect.describe(it))) }
        }

    // ------------------------------------------------------------------
    // 音频输出路由
    // ------------------------------------------------------------------

    /** 本地（App 进程）枚举音频输出 + 读当前路由，不需要 Shizuku。 */
    val audio = AudioRoutingController(
        contextProvider = { context },
        audioServiceBinder = null,
    )

    /** 诊断文本（含特权侧的真实路由状态）。 */
    suspend fun audioDiagnostics(): String = withContext(Dispatchers.IO) {
        service()?.let {
            runCatching { it.listAudioOutputs() }.getOrElse { t -> "调用失败: ${Reflect.describe(t)}" }
        } ?: audio.describe()
    }

    /**
     * 把音频输出切到指定设备。
     *
     * @param pinMedia 固定媒体音频（真正解决「一线连后耳机没声」，需要 Shizuku）
     * @param pinComm  固定通话音（公共 API，无需权限）
     */
    suspend fun setAudioOutputDevice(deviceId: Int, pinMedia: Boolean, pinComm: Boolean): OpResult =
        withContext(Dispatchers.IO) {
            val svc = service()
            if (svc != null) {
                runCatching {
                    val out = svc.setAudioOutputDevice(deviceId, pinMedia, pinComm)
                    val ok = out.contains("结果: 成功")
                    OpResult(ok, if (ok) "音频输出已切换" else "音频输出切换失败", out.lines().filter { it.isNotBlank() })
                }.getOrElse { OpResult(false, "调用 UserService 失败", listOf(Reflect.describe(it))) }
            } else {
                // 没有 Shizuku 时退回公共 API（只能改通话音）
                val r = audio.setCommunicationOutput(deviceId)
                OpResult(
                    r.ok,
                    if (r.ok) "已用公共 API 切换通话音频" else "无 Shizuku，且公共 API 也失败",
                    listOf(r.toText(), "提示：固定「媒体」音频必须要有 Shizuku 权限。"),
                )
            }
        }

    /** 清除音频输出偏好，恢复系统自动路由。 */
    suspend fun clearAudioOutputPreference(): OpResult = withContext(Dispatchers.IO) {
        val svc = service()
        if (svc != null) {
            runCatching {
                val out = svc.clearAudioOutputPreference()
                OpResult(true, "已清除音频输出偏好", out.lines().filter { it.isNotBlank() })
            }.getOrElse { OpResult(false, "清除失败", listOf(Reflect.describe(it))) }
        } else {
            val r = audio.clearCommunicationOutput()
            OpResult(r.ok, "已清除通话音频偏好", listOf(r.toText()))
        }
    }

    /** 当前媒体音频实际走哪个设备（App 侧公共 API 读数）。 */
    fun currentMediaOutputLabel(): String =
        audio.currentMediaOutput()?.let { "${AudioRoutingController.typeLabel(it.type)}" } ?: "(读不到)"

    // ------------------------------------------------------------------
    // 外接屏显示模式（扩展 / 复制 / 仅外接屏）
    // ------------------------------------------------------------------

    /**
     * 切到「扩展」模式：把外接屏变成独立屏幕。
     *
     * ⚠️ 这一步很重要：**Android 外接屏默认可能是镜像**。
     * 镜像状态下内屏和外屏共用同一个 layer stack，
     * 此时外屏分辨率改不动、关内屏还会把外屏一起黑掉。
     * 所以「改分辨率」之前建议先确保是扩展模式。
     */
    suspend fun applyExtendMode(externalDisplayId: Int): OpResult = withContext(Dispatchers.IO) {
        val (svc, err) = requireService()
        if (svc == null) return@withContext err!!
        runCatching {
            val out = svc.setExtendMode(externalDisplayId)
            val ok = out.contains("✅")
            OpResult(ok, if (ok) "已切换到扩展模式" else "切换扩展模式失败", out.lines().filter { it.isNotBlank() })
        }.getOrElse { OpResult(false, "调用失败", listOf(Reflect.describe(it))) }
    }

    /** 读外接屏的显示模式状态（windowingMode 等）。 */
    suspend fun displayModeState(externalDisplayId: Int): String = withContext(Dispatchers.IO) {
        service()?.let {
            runCatching { it.getDisplayModeState(externalDisplayId) }
                .getOrElse { t -> "调用失败: ${Reflect.describe(t)}" }
        } ?: "UserService 未连接"
    }

    /**
     * 当前外接屏是否处于镜像（复制）状态。
     *
     * 判据：外接屏与内屏的**逻辑尺寸完全相同**且两者都开启，
     * 且外接屏没有独立的 windowingMode。这只是启发式判断，
     * 权威结论以 Shizuku 侧读回的 windowingMode 为准。
     */
    fun looksMirrored(): Boolean {
        val internal = repo.internalDisplay() ?: return false
        val external = repo.primaryExternalDisplay() ?: return false
        if (internal.logicalWidth == 0 || external.logicalWidth == 0) return false
        return internal.logicalWidth == external.logicalWidth &&
            internal.logicalHeight == external.logicalHeight
    }

    // ------------------------------------------------------------------
    // 诊断汇总文本（第 15 节的“诊断信息”）
    // ------------------------------------------------------------------

    fun buildDiagnosticsText(
        displays: List<DisplaySnapshot>,
        shizukuState: String,
        extra: List<String> = emptyList(),
    ): String = buildString {
        appendLine("========== PadDisplay 诊断信息 ==========")
        appendLine("生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine("=== 设备 ===")
        appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
        appendLine("Brand: ${android.os.Build.BRAND}")
        appendLine("Model: ${android.os.Build.MODEL}")
        appendLine("Device: ${android.os.Build.DEVICE}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
        appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
        appendLine("Build: ${android.os.Build.DISPLAY}")
        appendLine("App: ${com.paddisplay.app.BuildConfig.VERSION_NAME} (${com.paddisplay.app.BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("=== Shizuku ===")
        appendLine(shizukuState)
        appendLine()
        appendLine("=== Displays (${displays.size}) ===")
        displays.forEach { d ->
            appendLine()
            appendLine("Display ${d.displayId}")
            appendLine("  Name: ${d.name}")
            appendLine("  Role: ${d.role}  Type: ${d.typeName}")
            appendLine("  Flags: ${d.flagNames.joinToString("|")} (0x${Integer.toHexString(d.flags)})")
            appendLine("  State: ${d.stateName}  Rotation: ${d.rotation}")
            appendLine("  Address: ${d.address}")
            appendLine("  PhysicalDisplayId: ${d.physicalDisplayId}  TokenAvailable: ${d.physicalTokenAvailable}")
            appendLine("  LogicalSize: ${d.logicalWidth}x${d.logicalHeight} @${d.logicalDensityDpi}dpi")
            appendLine("  CurrentMode: ${d.currentModeLabel}")
            appendLine("  PreferredMode: ${d.preferredMode?.label ?: "(不可读)"}")
            appendLine("  Evidence: ${d.evidence.joinToString("|")}")
            appendLine("  SupportedModes (${d.supportedModes.size}):")
            DisplayModeSelector.sortForDisplay(d.supportedModes).forEach { m ->
                appendLine("    modeId=${m.modeId} ${m.label}${if (m.isAlternative) " [alternative]" else ""}")
            }
        }
        if (extra.isNotEmpty()) {
            appendLine()
            appendLine("=== 附加信息 ===")
            extra.forEach { appendLine(it) }
        }
        appendLine()
        appendLine("========== 诊断信息结束 ==========")
    }
}
