package com.paddisplay.app.display

/**
 * 一块 Display.Mode 的不可变快照。
 *
 * 注意 [physicalWidth] / [physicalHeight] 是显示器**硬件真实report** 的物理分辨率，
 * 这是判断“显示器原生分辨率”的唯一可靠来源，不要用 Display.getSize()（那是逻辑尺寸，
 * 会被系统缩放 / forced size 覆盖污染）。
 */
data class DisplayModeInfo(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
    /** API 23+ 才有；某些 ColorOS 版本会给出奇怪的 alternative 标记，仅作展示。 */
    val isAlternative: Boolean = false,
) {
    val pixels: Long get() = physicalWidth.toLong() * physicalHeight.toLong()

    /** 人类可读，例如 3840 × 2160 @ 60 Hz */
    val label: String
        get() = "${physicalWidth} × ${physicalHeight} @ ${formatRefresh(refreshRate)}"

    /** 紧凑，例如 3840x2160@60 */
    val compact: String
        get() = "${physicalWidth}x${physicalHeight}@${formatRefresh(refreshRate)}"

    companion object {
        /** 把 60.0 -> "60"，59.94 -> "59.94" */
        fun formatRefresh(hz: Float): String {
            val rounded = Math.round(hz * 100f) / 100f
            return if (rounded == rounded.toInt().toFloat()) {
                rounded.toInt().toString()
            } else {
                rounded.toString()
            }
        }
    }
}

/** 屏幕角色，允许用户手动修正。 */
enum class DisplayRole {
    INTERNAL,
    EXTERNAL,
    UNKNOWN,
}

/** 屏幕类型的判断依据，用于诊断页面说明“为什么这样判定”。 */
enum class RoleEvidence {
    DEFAULT_DISPLAY,
    FLAG_BUILT_IN,
    FLAG_PRIVATE_AND_DEFAULT,
    FLAG_PRESENTATION,
    ADDRESS_LOCAL,
    ADDRESS_NON_LOCAL,
    PHYSICAL_TOKEN_MATCH,
    OWNER_PACKAGE,
    USER_OVERRIDE,
    FALLBACK,
}

/**
 * 一块显示器的完整快照，UI 与诊断页面都只依赖这个结构。
 */
data class DisplaySnapshot(
    val displayId: Int,
    val name: String,
    /** Display.type: 0=UNKNOWN 1=INTERNAL 2=EXTERNAL 3=WIFI 4=OVERLAY 5=VIRTUAL */
    val typeCode: Int,
    val typeName: String,
    val flags: Int,
    val flagNames: List<String>,
    val stateCode: Int,
    val stateName: String,
    val rotation: Int,
    val address: String?,
    /** 逻辑尺寸（可能被 forced size / 缩放影响）。 */
    val logicalWidth: Int,
    val logicalHeight: Int,
    val logicalDensityDpi: Int,
    val currentMode: DisplayModeInfo?,
    val supportedModes: List<DisplayModeInfo>,
    /** 系统上报的“首选模式”（通常等于原生分辨率）。 */
    val preferredMode: DisplayModeInfo?,
    val isDefaultDisplay: Boolean,
    val role: DisplayRole,
    val evidence: List<RoleEvidence>,
    /** 物理屏 id（SurfaceControl.getPhysicalDisplayIds 里的值），可为 null。 */
    val physicalDisplayId: Long?,
    /** 物理屏 token 是否可获取（单独关屏能力的前提）。 */
    val physicalTokenAvailable: Boolean,
) {
    val isInternal: Boolean get() = role == DisplayRole.INTERNAL
    val isExternal: Boolean get() = role == DisplayRole.EXTERNAL

    val typeLabel: String
        get() = when (role) {
            DisplayRole.INTERNAL -> "内置屏"
            DisplayRole.EXTERNAL -> "外接屏"
            DisplayRole.UNKNOWN -> "未知角色"
        }

    val currentModeLabel: String get() = currentMode?.label ?: "未知"

    /** 去重后的分辨率列表（用于“分辨率”单选）。 */
    val distinctResolutions: List<Pair<Int, Int>>
        get() = supportedModes
            .map { it.physicalWidth to it.physicalHeight }
            .distinct()
            .sortedByDescending { it.first.toLong() * it.second.toLong() }

    /** 某个分辨率下可用的刷新率。 */
    fun refreshRatesFor(width: Int, height: Int): List<Float> =
        supportedModes
            .filter { it.physicalWidth == width && it.physicalHeight == height }
            .map { it.refreshRate }
            .distinct()
            .sortedDescending()

    fun modesFor(width: Int, height: Int): List<DisplayModeInfo> =
        supportedModes
            .filter { it.physicalWidth == width && it.physicalHeight == height }
            .sortedByDescending { it.refreshRate }
}
