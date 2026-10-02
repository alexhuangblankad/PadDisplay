package com.paddisplay.app.display

import android.os.Build
import android.view.Display

/**
 * 判定“哪块是内屏、哪块是外屏”。
 *
 * 任务书明确要求：**不要假定 displayId == 0 就是内屏**。
 * 这里采用多证据判定，并把每一项证据都记录下来给“诊断信息”页面看，
 * 因为 ColorOS 上真正出问题时，你需要知道结论是依据哪一条得出的。
 *
 * 用户手动指定（[userOverride]）优先于所有自动判定。
 */
object DisplayDetector {

    /**
     * `android.view.Display.TYPE_*` 常量**全部是隐藏 API**，
     * 公共 android.jar 里只有 DEFAULT_DISPLAY / INVALID_DISPLAY。
     * 所以这里按 AOSP `Display.java` 的取值自己定义。
     */
    object DisplayType {
        const val UNKNOWN = 0
        const val INTERNAL = 1
        const val EXTERNAL = 2
        const val WIFI = 3
        const val OVERLAY = 4
        const val VIRTUAL = 5

        fun name(type: Int): String = when (type) {
            INTERNAL -> "INTERNAL(内置)"
            EXTERNAL -> "EXTERNAL(外接)"
            WIFI -> "WIFI(无线投屏)"
            OVERLAY -> "OVERLAY(叠加层)"
            VIRTUAL -> "VIRTUAL(虚拟)"
            else -> "UNKNOWN($type)"
        }
    }

    /**
     * 判定一块显示器是内屏还是外屏。
     *
     * 分两步走是为了避免“第一块被识别成外屏后，第二块内屏失去参照”的顺序依赖：
     * 先由 [isClearlyInternal] 找出无争议的内屏，再用 [resolveRole] 判定其余的。
     */
    fun detect(
        displayId: Int,
        typeCode: Int,
        flags: Int,
        address: String?,
        internalCount: Int,
        userOverride: Map<Int, DisplayRole> = emptyMap(),
    ): Pair<DisplayRole, List<RoleEvidence>> {
        val evidence = mutableListOf<RoleEvidence>()

        userOverride[displayId]?.let { forced ->
            evidence += RoleEvidence.USER_OVERRIDE
            return forced to evidence
        }

        if (isClearlyInternal(displayId, typeCode, flags, address, evidence)) {
            return DisplayRole.INTERNAL to evidence
        }

        val isDefault = displayId == Display.DEFAULT_DISPLAY
        val flagPresentation = (flags and Display.FLAG_PRESENTATION) != 0
        val flagPrivate = (flags and Display.FLAG_PRIVATE) != 0

        if (isDefault) evidence += RoleEvidence.DEFAULT_DISPLAY
        if (flagPresentation) evidence += RoleEvidence.FLAG_PRESENTATION

        // 明确的外接类型
        if (typeCode == DisplayType.EXTERNAL || typeCode == DisplayType.WIFI) {
            evidence += RoleEvidence.ADDRESS_NON_LOCAL
            return DisplayRole.EXTERNAL to evidence
        }

        // 物理地址：内屏是 local:<port>，port 0 为内屏，>0 为 DP Alt Mode 外接屏
        if (address != null) {
            if (address.startsWith("local:")) {
                evidence += RoleEvidence.ADDRESS_LOCAL
                val port = localPort(address)
                if (port != null && port > 0 && internalCount > 0) {
                    return DisplayRole.EXTERNAL to evidence
                }
            } else {
                evidence += RoleEvidence.ADDRESS_NON_LOCAL
                return DisplayRole.EXTERNAL to evidence
            }
        }

        // PRESENTATION + PRIVATE 且非默认屏 -> 外接
        if (flagPresentation && flagPrivate) {
            return DisplayRole.EXTERNAL to evidence
        }

        // 兜底：已经有内屏了，那这块就按外接处理
        if (internalCount > 0) {
            evidence += RoleEvidence.FALLBACK
            return DisplayRole.EXTERNAL to evidence
        }

        evidence += RoleEvidence.FALLBACK
        return DisplayRole.UNKNOWN to evidence
    }

    /** 无争议的内屏证据。命中时把证据写进 [evidence]。 */
    fun isClearlyInternal(
        displayId: Int,
        typeCode: Int,
        flags: Int,
        address: String?,
        evidence: MutableList<RoleEvidence>? = null,
    ): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) {
            evidence?.add(RoleEvidence.DEFAULT_DISPLAY)
            return true
        }
        if (typeCode == DisplayType.INTERNAL) {
            evidence?.add(RoleEvidence.FLAG_BUILT_IN)
            return true
        }
        if (address != null && address.startsWith("local:") && localPort(address) == 0) {
            evidence?.add(RoleEvidence.ADDRESS_LOCAL)
            return true
        }
        return false
    }

    /** "local:4630947232119655425" -> 4630947232119655425（低 8 位是端口号） */
    fun physicalIdOf(address: String?): Long? {
        if (address == null) return null
        val raw = address.substringAfter("local:", "")
        if (raw.isEmpty() || raw == address) return null
        return raw.toLongOrNull()
    }

    /** 端口号：内屏为 0，DP Alt Mode 外接屏通常为 1+。 */
    fun localPort(address: String): Int? =
        physicalIdOf(address)?.let { (it and 0xFFL).toInt() }

    val apiLevel: Int get() = Build.VERSION.SDK_INT
}
