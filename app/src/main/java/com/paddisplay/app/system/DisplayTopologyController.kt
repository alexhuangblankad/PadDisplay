package com.paddisplay.app.system

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * 多屏拓扑（屏幕相对位置）控制 —— Android 版的「Windows 显示布局」。
 *
 * ## 为什么这是关键机制
 *
 * AOSP 的 `DisplayManagerService` 在 feature flag 打开时会把拓扑**喂给输入系统**：
 *
 * ```java
 * if (mFlags.isDisplayTopologyEnabled()) {
 *     DisplayTopologyGraph graph = update.second;
 *     mInputManagerInternal.setDisplayTopology(graph);   // ← 输入系统
 * }
 * ```
 *
 * 也就是说：**拓扑不只是"画个位置图"，它建立了多屏统一的坐标空间，
 * 并据此让光标/输入能够跨屏**。这正是用户要的"像 Windows 一样决定两块屏的
 * 左右关系、把鼠标拖过去"的机制。
 *
 * ## ⚠️ 必须探测，不能盲报成功
 *
 * 该能力被 feature flag 控制。flag 关闭时服务端：
 *
 * ```java
 * public DisplayTopology getDisplayTopology() {
 *     if (mDisplayTopologyCoordinator == null) return null;   // ← 返回 null
 * }
 * public void setDisplayTopology(DisplayTopology topology) {
 *     if (mDisplayTopologyCoordinator != null) { ... }        // ← 空操作，不报错
 * }
 * ```
 *
 * 所以：
 * - `getDisplayTopology()` 返回 null 或抛异常 ⇒ **本机不支持**，必须如实告知；
 * - `setDisplayTopology()` 后必须 `getDisplayTopology()` **读回验证**，
 *   否则就是"调用了没报错但其实什么都没做"。
 *
 * ## 事务号
 *
 * 由 AOSP `IDisplayManager.aidl`（android-16.0.0_r1）解析，
 * 并用真实 `aidl.exe` 编译同序骨架验证：`getDisplayTopology = 63`、`setDisplayTopology = 64`。
 */
class DisplayTopologyController(private val displayManagerBinder: IBinder?) {

    companion object {
        private const val CLASS_TOPOLOGY = "android.hardware.display.DisplayTopology"
        private const val CLASS_TREE_NODE = "android.hardware.display.DisplayTopology\$TreeNode"

        /** `DisplayTopology.TreeNode.POSITION_*` */
        const val POSITION_LEFT = 0
        const val POSITION_TOP = 1
        const val POSITION_RIGHT = 2
        const val POSITION_BOTTOM = 3
    }

    /**
     * transaction code。
     *
     * ⚠️ 参考项目 Dextop 的 `DisplayTopologyController` 里有一条明确警告：
     *
     * > Transaction ids ... are not stable across Android releases or OEM framework forks.
     * > **Never use a numeric fallback here**: on newer builds the old id may point at
     * > `requestDisplayModes()`, which is protected by `RESTRICT_DISPLAY_MODES`.
     *
     * 所以本实现**优先从 `IDisplayManager$Stub` 读真实常量**；
     * 读不到就**判定为不支持**，绝不退回硬编码数字。
     */
    private val resolvedCodes: Pair<Int, Int>? by lazy {
        runCatching {
            val stub = Reflect.classForName("android.hardware.display.IDisplayManager\$Stub")
                ?: return@runCatching null
            fun read(name: String): Int? = runCatching {
                val f = stub.getDeclaredField(name)
                f.isAccessible = true
                f.getInt(null)
            }.getOrNull()
            val get = read("TRANSACTION_getDisplayTopology")
            val set = read("TRANSACTION_setDisplayTopology")
            if (get != null && set != null) get to set else null
        }.getOrNull()
    }

    /** 本机是否支持读取/写入显示拓扑（以能否解析到真实事务号为准）。 */
    fun isSupported(): Boolean = resolvedCodes != null && displayManagerBinder != null

    /**
     * 保存当前拓扑快照，供「一键还原」用。
     *
     * `setDisplayTopology` 是**整体替换**系统拓扑，不先存原始值就还不了原。
     * 返回值：`null` = 本机不支持；`true to null` = 原本无拓扑；
     * `true to bytes` = 原始拓扑的 Parcel 字节。
     */
    fun snapshotTopologyBytes(): Pair<Boolean, ByteArray?>? {
        val codes = resolvedCodes ?: return null
        return AidlCodec.call(
            binder = displayManagerBinder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "snapshotTopology",
            code = codes.first,
            writeArgs = { },
            readReply = { reply ->
                val present = reply.readInt()
                if (present == 0) {
                    true to null
                } else {
                    // 逐字段读一遍再序列化回去，避免直接抓 parcel 字节（长度不定）
                    val topo = readParcelableFromReply(reply, CLASS_TOPOLOGY)
                    true to serializeTopology(topo)
                }
            },
        ).getOrNull()
    }

    /**
     * 把拓扑对象序列化成字节。
     *
     * `DisplayTopology` 的 Parcel 布局是：`[1][root: 字段…][primaryDisplayId]`，
     * 与 AOSP 的 `writeToParcel` 一致（参考项目 Dextop 也是按这个布局手写的）。
     */
    private fun serializeTopology(topology: Any?): ByteArray? {
        if (topology == null) return null
        return runCatching {
            val p = Parcel.obtain()
            try {
                @Suppress("UNCHECKED_CAST")
                (topology as Parcelable).writeToParcel(p, 0)
                p.marshall()
            } finally {
                p.recycle()
            }
        }.getOrNull()
    }

    /** 用字节快照还原拓扑。 */
    fun restoreTopologyBytes(bytes: ByteArray?): Report {
        val codes = resolvedCodes
            ?: return Report(false, "setDisplayTopology", "无法解析真实事务号，已放弃")
        val r = AidlCodec.callVoid(
            binder = displayManagerBinder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "setDisplayTopology(restore)",
            code = codes.second,
            writeArgs = { data ->
                if (bytes == null) {
                    data.writeInt(0)
                } else {
                    data.writeInt(1)
                    data.writeByteArray(bytes)
                }
            },
        )
        return r.fold(
            onSuccess = {
                Report(true, "setDisplayTopology", if (bytes == null) "已还原为「无拓扑」" else "已写回拓扑快照")
            },
            onFailure = { Report(false, "setDisplayTopology", Reflect.describe(it)) },
        )
    }

    data class Report(val ok: Boolean, val channel: String, val detail: String) {
        fun toText(): String = if (ok) "✅ $channel: $detail" else "❌ $channel: $detail"
    }

    /** 能力探测结果。 */
    data class Capability(
        val supported: Boolean,
        val detail: String,
    )

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private fun readTopology(): Any? {
        // 解析不到真实事务号 ⇒ 判定不支持，绝不用硬编码数字去 transact
        val codes = resolvedCodes ?: return null
        return AidlCodec.call(
            binder = displayManagerBinder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "getDisplayTopology",
            code = codes.first,
            writeArgs = { },
            readReply = { reply ->
                val present = reply.readInt()
                if (present == 0) null else readParcelableFromReply(reply, CLASS_TOPOLOGY)
            },
        ).getOrNull()
    }

    private fun readParcelableFromReply(reply: Parcel, className: String): Any? {
        val clazz = Reflect.classForName(className) ?: return null
        val creator = runCatching {
            clazz.getField("CREATOR").get(null) as? Parcelable.Creator<*>
        }.getOrNull() ?: return null
        return runCatching { creator.createFromParcel(reply) }.getOrNull()
    }

    /**
     * 探测本机是否支持多屏拓扑。
     *
     * `getDisplayTopology()` 返回 null ⇒ feature flag 关闭（服务端 coordinator 为 null）。
     */
    fun probe(): Capability {
        if (displayManagerBinder == null) {
            return Capability(false, "取不到 display 系统服务")
        }
        val codes = resolvedCodes
            ?: return Capability(
                false,
                "无法从 IDisplayManager\$Stub 解析 TRANSACTION_getDisplayTopology —— " +
                    "本机不提供该能力的可靠事务号，按不支持处理（不使用硬编码数字，避免误调其它方法）。",
            )
        val result = AidlCodec.call(
            binder = displayManagerBinder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "getDisplayTopology",
            code = codes.first,
            writeArgs = { },
            readReply = { reply ->
                val present = reply.readInt()
                if (present == 0) null else readParcelableFromReply(reply, CLASS_TOPOLOGY)
            },
        )
        return result.fold(
            onSuccess = { topology ->
                if (topology == null) {
                    Capability(
                        false,
                        "系统返回 null —— 说明 DisplayTopology 的 feature flag 在本机关闭，"
                            + "该能力不可用（服务端 setDisplayTopology 会静默无操作）。",
                    )
                } else {
                    Capability(true, "支持。当前拓扑：${describeTopology(topology)}")
                }
            },
            onFailure = { t ->
                Capability(false, "调用失败（可能被 ROM 移除或权限不足）：${Reflect.describe(t)}")
            },
        )
    }

    /** 把拓扑对象转成可读文本。 */
    fun describeTopology(topology: Any?): String {
        if (topology == null) return "(null)"
        val sb = StringBuilder()
        val primary = runCatching {
            Reflect.findMethod(topology.javaClass, "getPrimaryDisplayId")?.invoke(topology) as? Int
        }.getOrNull()
        sb.append("primaryDisplayId=$primary")
        val root = runCatching {
            Reflect.findMethod(topology.javaClass, "getRoot")?.invoke(topology)
        }.getOrNull()
        if (root == null) {
            sb.append(" root=(null)")
        } else {
            sb.append("\n  显示树：")
            appendNode(sb, root, "  ")
        }
        // 绝对边界最能说明布局
        val bounds = runCatching {
            Reflect.findMethod(topology.javaClass, "getAbsoluteBounds")?.invoke(topology)
        }.getOrNull()
        if (bounds != null) {
            sb.append("\n  绝对边界：$bounds")
        }
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: Any, indent: String) {
        val id = runCatching {
            Reflect.findMethod(node.javaClass, "getDisplayId")?.invoke(node) as? Int
        }.getOrNull()
        val w = runCatching {
            Reflect.findMethod(node.javaClass, "getWidth")?.invoke(node) as? Float
        }.getOrNull()
        val h = runCatching {
            Reflect.findMethod(node.javaClass, "getHeight")?.invoke(node) as? Float
        }.getOrNull()
        val pos = runCatching {
            Reflect.findMethod(node.javaClass, "getPosition")?.invoke(node) as? Int
        }.getOrNull()
        val off = runCatching {
            Reflect.findMethod(node.javaClass, "getOffset")?.invoke(node) as? Float
        }.getOrNull()
        sb.append("\n$indent displayId=$id size=${w}x$h position=${positionName(pos)} offset=$off")
        val children = runCatching {
            Reflect.findMethod(node.javaClass, "getChildren")?.invoke(node) as? List<*>
        }.getOrNull()
        children?.forEach { c -> if (c != null) appendNode(sb, c, "$indent  ") }
    }

    private fun positionName(pos: Int?): String = when (pos) {
        POSITION_LEFT -> "LEFT(0)"
        POSITION_TOP -> "TOP(1)"
        POSITION_RIGHT -> "RIGHT(2)"
        POSITION_BOTTOM -> "BOTTOM(3)"
        else -> "POS($pos)"
    }

    // ------------------------------------------------------------------
    // 写入：设置左右顺序
    // ------------------------------------------------------------------

    /**
     * 设置两块屏的相对位置（支持左/上/右/下四个方向）。
     *
     * @param position 0=左 1=上 2=右 3=下（与 AOSP `TreeNode.POSITION_*` 一致）
     */
    fun setLayout(
        primaryDisplayId: Int,
        otherDisplayId: Int,
        position: Int,
        primarySize: Pair<Int, Int>,
        otherSize: Pair<Int, Int>,
    ): List<Report> {
        val reports = mutableListOf<Report>()

        val topologyClass = Reflect.classForName(CLASS_TOPOLOGY)
            ?: return listOf(Report(false, "DisplayTopology", "找不到 DisplayTopology 类"))
        val treeNodeClass = Reflect.classForName(CLASS_TREE_NODE)
            ?: return listOf(Report(false, "TreeNode", "找不到 DisplayTopology\$TreeNode 类"))

        // 1) new DisplayTopology()
        val topology = runCatching {
            topologyClass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        }.getOrNull() ?: return listOf(
            Report(false, "DisplayTopology()", "构造失败（无默认构造器）"),
        )

        // 2) addDisplay(id, w, h)
        val addDisplay = Reflect.findMethod(
            topologyClass,
            "addDisplay",
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        ) ?: return listOf(Report(false, "addDisplay", "方法不存在"))

        runCatching {
            addDisplay.invoke(topology, primaryDisplayId, primarySize.first.toFloat(), primarySize.second.toFloat())
            addDisplay.invoke(topology, otherDisplayId, otherSize.first.toFloat(), otherSize.second.toFloat())
        }.onFailure {
            return listOf(Report(false, "addDisplay", Reflect.describe(it)))
        }

        // 3) 构造 TreeNode 树：根 = primary，子 = other，位置 LEFT 或 RIGHT
        // position 直接来自调用方（0=左 1=上 2=右 3=下）
        val childNode = runCatching {
            treeNodeClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
                .newInstance(otherDisplayId, otherSize.first.toFloat(), otherSize.second.toFloat(), position, 0f)
        }.getOrNull() ?: return listOf(Report(false, "TreeNode", "构造失败"))

        val rootNode = runCatching {
            treeNodeClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                List::class.java,
            ).also { it.isAccessible = true }
                .newInstance(
                    primaryDisplayId,
                    primarySize.first.toFloat(),
                    primarySize.second.toFloat(),
                    POSITION_RIGHT, // 根节点没有父节点，position 无意义
                    0f,
                    listOf(childNode),
                )
        }.getOrNull() ?: return listOf(Report(false, "TreeNode(root)", "构造失败"))

        val topologyWithTree = runCatching {
            topologyClass.getDeclaredConstructor(
                treeNodeClass,
                Int::class.javaPrimitiveType,
            ).also { it.isAccessible = true }.newInstance(rootNode, primaryDisplayId)
        }.getOrNull() ?: return listOf(Report(false, "DisplayTopology(TreeNode,int)", "构造失败"))

        // 4) 写入
        val codes = resolvedCodes ?: return reports + Report(
            false,
            "setDisplayTopology",
            "无法解析真实事务号，为避免误调其它方法（例如受 RESTRICT_DISPLAY_MODES 保护的 requestDisplayModes），已放弃写入",
        )
        val writeResult = AidlCodec.callVoid(
            binder = displayManagerBinder,
            descriptor = AidlCodec.DESCRIPTOR_DISPLAY_MANAGER,
            label = "setDisplayTopology",
            code = codes.second,
            writeArgs = { data ->
                data.writeInt(1)
                @Suppress("UNCHECKED_CAST")
                (topologyWithTree as Parcelable).writeToParcel(data, 0)
            },
        )
        reports += writeResult.fold(
            onSuccess = {
                Report(
                    true,
                    "setDisplayTopology",
                    "已提交：$primaryDisplayId 为原点，$otherDisplayId 在${positionName(position)}",
                )
            },
            onFailure = { Report(false, "setDisplayTopology", Reflect.describe(it)) },
        )
        if (reports.any { !it.ok }) return reports

        // 5) 读回验证（关键：flag 关闭时写入是空操作，只有读回能发现）
        val back = readTopology()
        val backText = describeTopology(back)
        val verified = back != null && (
            backText.contains("displayId=$primaryDisplayId") &&
                backText.contains("displayId=$otherDisplayId")
            )
        reports += if (verified) {
            Report(true, "读回验证", "拓扑已生效：$backText")
        } else {
            Report(
                false,
                "读回验证",
                "写入返回成功但读回不一致 —— 很可能本机的 DisplayTopology " +
                    "feature flag 关闭，服务端静默忽略了这次写入。读回：$backText",
            )
        }
        return reports
    }

    /** 诊断文本。 */
    fun describe(): String = buildString {
        appendLine("display binder: ${AidlCodec.describeBinder(displayManagerBinder)}")
        appendLine("code: " + (resolvedCodes?.let { "从 Stub 解析 get=${it.first} set=${it.second}" } ?: "无法解析（判定为不支持）"))
        appendLine("DisplayTopology 类: ${if (Reflect.classForName(CLASS_TOPOLOGY) != null) "可用" else "不可用"}")
        appendLine("TreeNode 类: ${if (Reflect.classForName(CLASS_TREE_NODE) != null) "可用" else "不可用"}")
        val cap = probe()
        appendLine("能力探测: supported=${cap.supported}")
        appendLine("  ${cap.detail}")
    }
}
