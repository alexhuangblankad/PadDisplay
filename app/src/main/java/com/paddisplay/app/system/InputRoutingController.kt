package com.paddisplay.app.system

import android.os.IBinder
import android.os.Parcel
import android.view.Display
import android.view.InputDevice

/**
 * 把输入设备（触摸屏 / 鼠标 / 键盘）路由到指定的外接显示器。
 *
 * ## 为什么这是「扩展模式」的最后一块拼图
 *
 * 实测结论（用户在 OPPO Pad mini + ColorOS + 4K 便携屏上验证）：
 * - 外接屏**能**独立渲染满屏 4K（`am start --display 13` 成功）
 * - 但**鼠标到不了外接屏、也无法操作**，于是只能退回去用系统的「复制模式」
 *
 * 原因：**Android 默认不把输入设备绑定到外接屏**。
 * 内屏的触摸屏只对内屏生效，鼠标被限制在默认屏内。
 * 镜像模式下输入会自动跟随，所以 ColorOS 用"复制"回避了这个问题。
 *
 * 参考项目 `android-display-extend` 的 `InputRouting` 正是解决这件事，
 * 本实现参照其思路（三级回退），但**按 Android 16 的实际 AIDL 修正了方法名**：
 *
 * | 级别 | 方法 | 备注 |
 * |---|---|---|
 * | 1 | `addUniqueIdAssociationByDescriptor(descriptor, displayUniqueId)` | 首选 |
 * | 2 | `addUniqueIdAssociationByPort(inputPort, displayUniqueId)` | 端口 |
 * | 3 | `addPortAssociation(inputPort, displayPort)` | 最后手段 |
 *
 * ⚠️ 参考项目用的 `addUniqueIdAssociation(String, String)` /
 * `removeUniqueIdAssociation(String)` 在 **API 36 的 AIDL 里已经不存在**
 * （被 `...ByPort` 取代），照抄会在 Android 16 上直接失效。
 *
 * 事务号同样用真实 `aidl.exe` 编译同序骨架验证过（见 tools/verify-aidl-codes.ps1）。
 */
class InputRoutingController(
    private val inputManagerBinder: IBinder?,
    private val shellRunner: (String) -> String,
) {

    companion object {
        const val DESCRIPTOR_INPUT_MANAGER = "android.hardware.input.IInputManager"

        /** IInputManager.aidl 解析 + 真实编译器验证（API 36）。 */
        private object Codes {
            const val GET_INPUT_DEVICE = 2
            const val GET_INPUT_DEVICE_IDS = 3
            const val ADD_PORT_ASSOCIATION = 40
            const val REMOVE_PORT_ASSOCIATION = 41
            const val ADD_UNIQUE_ID_BY_DESCRIPTOR = 42
            const val REMOVE_UNIQUE_ID_BY_DESCRIPTOR = 43
            const val ADD_UNIQUE_ID_BY_PORT = 44
            const val REMOVE_UNIQUE_ID_BY_PORT = 45
        }

        /**
         * 外接输入设备：有 Location 且不是虚拟设备。
         *
         * ⚠️ `InputDevice.getLocation()` 是**隐藏方法**，必须反射。
         */
        fun locationOf(device: InputDevice): String =
            runCatching {
                Reflect.findMethod(device.javaClass, "getLocation")?.invoke(device) as? String
            }.getOrNull() ?: ""

        fun isExternalInputDevice(device: InputDevice): Boolean {
            val location = locationOf(device)
            if (location.isBlank()) return false
            return !runCatching { device.isVirtual }.getOrDefault(false)
        }
    }

    data class Report(val ok: Boolean, val channel: String, val detail: String) {
        fun toText(): String = if (ok) "✅ $channel: $detail" else "❌ $channel: $detail"
    }

    /** 一个输入设备的摘要。 */
    data class InputDeviceInfo(
        val id: Int,
        val name: String,
        val descriptor: String,
        val location: String,
        /** `InputDevice.isExternal()` —— 公共 API，外接设备判定的正确依据 */
        val isExternal: Boolean,
        val isVirtual: Boolean,
        val isMouse: Boolean,
        val isKeyboard: Boolean,
        /** `InputDevice.getAssociatedDisplayId()`（隐藏 API）：当前关联到哪块屏 */
        val associatedDisplayId: Int?,
    ) {
        val purpose: String
            get() = when {
                isMouse && isKeyboard -> "鼠标+键盘"
                isMouse -> "鼠标/指针"
                isKeyboard -> "键盘"
                else -> "其他"
            }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 枚举输入设备。
     *
     * ⚠️ 关键修正（对照参考项目 Dextop 的 `PhysicalDeviceRouting`）：
     * 判定"是否外接"必须用**公共 API `InputDevice.isExternal()`**，
     * 而不是像早先那样靠 `getLocation()` 字符串非空 ——
     * 在某些 ROM 上 `getLocation()` 返回空，会把**所有设备都过滤掉**，
     * 于是永远报"没有找到可绑定的外接输入设备"。
     */
    fun listInputDevices(): List<InputDeviceInfo> =
        InputDevice.getDeviceIds().toList().mapNotNull { id ->
            if (id < 0) return@mapNotNull null
            val dev = runCatching { InputDevice.getDevice(id) }.getOrNull() ?: return@mapNotNull null
            val sources = runCatching { dev.sources }.getOrDefault(0)
            val isMouse = (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            val isKeyboard = runCatching {
                dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            }.getOrDefault(false)
            InputDeviceInfo(
                id = id,
                name = runCatching { dev.name }.getOrNull() ?: "?",
                descriptor = runCatching { dev.descriptor }.getOrNull() ?: "",
                location = locationOf(dev),
                isExternal = runCatching { dev.isExternal }.getOrDefault(false),
                isVirtual = runCatching { dev.isVirtual }.getOrDefault(false),
                isMouse = isMouse,
                isKeyboard = isKeyboard,
                associatedDisplayId = associatedDisplayIdOf(dev),
            )
        }

    /** `InputDevice.getAssociatedDisplayId()` 是隐藏 API，用反射读（读回验证用）。 */
    private fun associatedDisplayIdOf(device: InputDevice): Int? = runCatching {
        Reflect.findMethod(device.javaClass, "getAssociatedDisplayId")?.invoke(device) as? Int
    }.getOrNull()

    /**
     * 从 `dumpsys input` 解析「设备描述符 -> 输入端口」映射。
     *
     * 为什么需要它：`addUniqueIdAssociationByDescriptor` 在部分设备/部分设备类型上会失败，
     * 此时要用端口来关联，而 `InputDevice` 本身拿不到端口，只能从 dumpsys 里解析。
     * 输出形如：
     * ```
     *   Descriptor: 1c2d3e...
     *   Location: usb-1.2/input0
     * ```
     */
    fun descriptorToPortMap(): Map<String, String> {
        val dump = runCatching { shellRunner("/system/bin/dumpsys input") }.getOrNull() ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        var lastDescriptor: String? = null
        dump.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("Descriptor:")) {
                lastDescriptor = line.removePrefix("Descriptor:").trim()
            } else if (line.startsWith("Location:")) {
                val loc = line.removePrefix("Location:").trim()
                lastDescriptor?.let { if (it.isNotEmpty()) map[it] = loc }
            }
        }
        return map
    }

    /**
     * 取显示器的 `uniqueId` 与物理端口。
     *
     * `DisplayInfo.uniqueId` 是输入关联用的稳定标识；
     * 端口在 `DisplayInfo.address`（`DisplayAddress.Physical`）里，
     * 形如 `{port=21, model=0x...}`。
     */
    fun displayIdentity(display: Display): Triple<String?, Int?, String> {
        val info = runCatching {
            val clazz = Reflect.classForName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = Reflect.findMethod(clazz, "getInstance") ?: return Triple(null, null, "无 DisplayManagerGlobal")
            val global = getInstance.invoke(null) ?: return Triple(null, null, "无 global")
            val m = Reflect.findMethod(global.javaClass, "getDisplayInfo", Int::class.javaPrimitiveType)
                ?: return Triple(null, null, "无 getDisplayInfo")
            m.invoke(global, display.displayId)
        }.getOrNull() ?: return Triple(null, null, "读不到 DisplayInfo")

        val uniqueId = Reflect.getField(info, "uniqueId").getOrNull() as? String

        // address 是 DisplayAddress，Physical 子类有 getPort()
        val address = Reflect.getField(info, "address").getOrNull()
        var port: Int? = null
        var addrText = "(无)"
        if (address != null) {
            addrText = address.toString()
            port = runCatching {
                val m = Reflect.findMethod(address.javaClass, "getPort")
                m?.invoke(address) as? Int
            }.getOrNull()
            if (port == null) {
                // 有些版本 toString 形如 {port=21, model=0x...}
                port = Regex("""port=(\d+)""").find(addrText)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        return Triple(uniqueId, port, "uniqueId=$uniqueId address=$addrText port=$port")
    }

    // ------------------------------------------------------------------
    // 关联 / 解除关联
    // ------------------------------------------------------------------

    private fun addUniqueIdByDescriptor(descriptor: String, displayUniqueId: String): Report =
        AidlCodec.callVoid(
            binder = inputManagerBinder,
            descriptor = DESCRIPTOR_INPUT_MANAGER,
            label = "addUniqueIdAssociationByDescriptor",
            code = Codes.ADD_UNIQUE_ID_BY_DESCRIPTOR,
            writeArgs = { data ->
                data.writeString(descriptor)
                data.writeString(displayUniqueId)
            },
        ).fold(
            onSuccess = { Report(true, "addUniqueIdAssociationByDescriptor", "$descriptor -> $displayUniqueId") },
            onFailure = { Report(false, "addUniqueIdAssociationByDescriptor", Reflect.describe(it)) },
        )

    private fun removeUniqueIdByDescriptor(descriptor: String): Report =
        AidlCodec.callVoid(
            binder = inputManagerBinder,
            descriptor = DESCRIPTOR_INPUT_MANAGER,
            label = "removeUniqueIdAssociationByDescriptor",
            code = Codes.REMOVE_UNIQUE_ID_BY_DESCRIPTOR,
            writeArgs = { it.writeString(descriptor) },
        ).fold(
            onSuccess = { Report(true, "removeUniqueIdAssociationByDescriptor", descriptor) },
            onFailure = { Report(false, "removeUniqueIdAssociationByDescriptor", Reflect.describe(it)) },
        )

    private fun addUniqueIdByPort(inputPort: String, displayUniqueId: String): Report =
        AidlCodec.callVoid(
            binder = inputManagerBinder,
            descriptor = DESCRIPTOR_INPUT_MANAGER,
            label = "addUniqueIdAssociationByPort",
            code = Codes.ADD_UNIQUE_ID_BY_PORT,
            writeArgs = { data ->
                data.writeString(inputPort)
                data.writeString(displayUniqueId)
            },
        ).fold(
            onSuccess = { Report(true, "addUniqueIdAssociationByPort", "$inputPort -> $displayUniqueId") },
            onFailure = { Report(false, "addUniqueIdAssociationByPort", Reflect.describe(it)) },
        )

    private fun addPortAssociation(inputPort: String, displayPort: Int): Report =
        AidlCodec.callVoid(
            binder = inputManagerBinder,
            descriptor = DESCRIPTOR_INPUT_MANAGER,
            label = "addPortAssociation",
            code = Codes.ADD_PORT_ASSOCIATION,
            writeArgs = { data ->
                data.writeString(inputPort)
                data.writeInt(displayPort)
            },
        ).fold(
            onSuccess = { Report(true, "addPortAssociation", "$inputPort -> port $displayPort") },
            onFailure = { Report(false, "addPortAssociation", Reflect.describe(it)) },
        )

    /**
     * 挑出可绑定的设备。
     *
     * 对照 Dextop 的 `eligibleDevices`：用**公共 API `isExternal`** + descriptor 非空，
     * 再按鼠标/键盘类型筛选。**不再用 `getLocation()` 是否为空来判定** ——
     * 那正是"6 个设备全被过滤掉"的原因。
     */
    private fun eligibleDevices(mouse: Boolean, keyboard: Boolean): List<InputDeviceInfo> =
        listInputDevices().filter { d ->
            d.isExternal && d.descriptor.isNotBlank() &&
                ((mouse && d.isMouse) || (keyboard && d.isKeyboard))
        }

    /**
     * 把**外接输入设备**绑定到指定显示器。
     *
     * @param includeMouse 绑定鼠标/指针设备（推荐 true）
     * @param includeKeyboard 是否连键盘一起绑定。
     *   **默认 false**：Android 的设备关联是静态的，把键盘绑到外屏后它就只往外屏送键事件；
     *   而键事件本身走**焦点窗口**，不绑反而能让两块屏按焦点各自接收键盘输入。
     */
    fun bindAllExternalInputToDisplay(
        display: Display,
        includeMouse: Boolean = true,
        includeKeyboard: Boolean = false,
    ): List<Report> {
        val reports = mutableListOf<Report>()
        if (inputManagerBinder == null) {
            return listOf(Report(false, "IInputManager", "取不到 input 系统服务（需要 Shizuku）"))
        }

        val (uniqueId, displayPort, idText) = displayIdentity(display)
        reports += Report(true, "目标显示器", "displayId=${display.displayId} $idText")
        if (uniqueId.isNullOrEmpty() && displayPort == null) {
            reports += Report(false, "定位显示器", "拿不到 uniqueId 也拿不到物理端口，无法关联输入设备")
            return reports
        }

        val devices = eligibleDevices(mouse = includeMouse, keyboard = includeKeyboard)
        if (devices.isEmpty()) {
            val all = listInputDevices()
            reports += Report(
                false,
                "可绑定设备",
                "没有符合条件的设备（mouse=$includeMouse keyboard=$includeKeyboard）；" +
                    "当前共 ${all.size} 个输入设备：",
            )
            all.forEach { d ->
                reports += Report(
                    true,
                    "  ·",
                    "id=${d.id} name=${d.name} external=${d.isExternal} virtual=${d.isVirtual} " +
                        "${d.purpose} assoc=${d.associatedDisplayId ?: "-"}",
                )
            }
            return reports
        }
        reports += Report(true, "可绑定设备", devices.joinToString { "${it.name}(${it.purpose})" })

        val portMap = descriptorToPortMap()

        devices.forEach { dev ->
            val inputPort = portMap[dev.descriptor] ?: dev.location
            var bound = false

            // 级别 1：按描述符关联 uniqueId（Dextop 的做法，最可靠）
            if (!uniqueId.isNullOrEmpty()) {
                val r1 = addUniqueIdByDescriptor(dev.descriptor, uniqueId)
                reports += r1
                bound = r1.ok
                // 级别 2：按端口关联 uniqueId
                if (!bound && inputPort.isNotBlank()) {
                    val r2 = addUniqueIdByPort(inputPort, uniqueId)
                    reports += r2
                    bound = r2.ok
                }
            }

            // 级别 3：按端口关联物理端口
            if (!bound && displayPort != null && inputPort.isNotBlank()) {
                val r3 = addPortAssociation(inputPort, displayPort)
                reports += r3
                bound = r3.ok
            }

            if (!bound) {
                reports += Report(false, "绑定失败", "${dev.name} 三级回退全部失败")
            }
        }

        // ---- 读回验证：用 getAssociatedDisplayId() 确认真的关联上了 ----
        val after = listInputDevices().filter { d -> devices.any { it.id == d.id } }
        val associated = after.filter { it.associatedDisplayId == display.displayId }
        reports += if (associated.isNotEmpty()) {
            Report(true, "读回验证", "已关联到 displayId=${display.displayId}：" + associated.joinToString { it.name })
        } else {
            Report(
                false,
                "读回验证",
                "写入返回成功，但 getAssociatedDisplayId() 读回未显示关联到 displayId=${display.displayId}" +
                    "（实际：" + after.joinToString { "${it.name}=${it.associatedDisplayId ?: "-"}" } + "）",
            )
        }
        return reports
    }

    /** 解除所有输入设备的关联，恢复默认（输入跟随默认屏）。 */
    fun clearAllAssociations(): List<Report> {
        val reports = mutableListOf<Report>()
        if (inputManagerBinder == null) {
            return listOf(Report(false, "IInputManager", "取不到 input 系统服务"))
        }
        val devices = listInputDevices()
        val portMap = descriptorToPortMap()
        devices.forEach { dev ->
            reports += removeUniqueIdByDescriptor(dev.descriptor)
            val inputPort = portMap[dev.descriptor] ?: dev.location
            if (inputPort.isNotBlank()) {
                AidlCodec.callVoid(
                    binder = inputManagerBinder,
                    descriptor = DESCRIPTOR_INPUT_MANAGER,
                    label = "removeUniqueIdAssociationByPort",
                    code = Codes.REMOVE_UNIQUE_ID_BY_PORT,
                    writeArgs = { it.writeString(inputPort) },
                ).onSuccess {
                    reports += Report(true, "removeUniqueIdAssociationByPort", inputPort)
                }
                AidlCodec.callVoid(
                    binder = inputManagerBinder,
                    descriptor = DESCRIPTOR_INPUT_MANAGER,
                    label = "removePortAssociation",
                    code = Codes.REMOVE_PORT_ASSOCIATION,
                    writeArgs = { it.writeString(inputPort) },
                ).onSuccess {
                    reports += Report(true, "removePortAssociation", inputPort)
                }
            }
        }
        return reports
    }

    /** 诊断。 */
    fun describe(): String = buildString {
        appendLine("input binder: ${AidlCodec.describeBinder(inputManagerBinder)}")
        appendLine("code: getInputDeviceIds=${Codes.GET_INPUT_DEVICE_IDS} getInputDevice=${Codes.GET_INPUT_DEVICE}")
        appendLine("      byDescriptor=${Codes.ADD_UNIQUE_ID_BY_DESCRIPTOR}/${Codes.REMOVE_UNIQUE_ID_BY_DESCRIPTOR}")
        appendLine("      byPort=${Codes.ADD_UNIQUE_ID_BY_PORT}/${Codes.REMOVE_UNIQUE_ID_BY_PORT}")
        appendLine("      portAssociation=${Codes.ADD_PORT_ASSOCIATION}/${Codes.REMOVE_PORT_ASSOCIATION}")
        appendLine("（API 36 已移除 addUniqueIdAssociation/removeUniqueIdAssociation，故不使用）")
        appendLine()
        appendLine("输入设备:")
        val list = listInputDevices()
        if (list.isEmpty()) {
            appendLine("  (读不到，可能需要 Shizuku)")
        } else {
            list.forEach { d ->
                appendLine(
                    "  id=${d.id} name=${d.name} external=${d.isExternal} virtual=${d.isVirtual} " +
                        "location=${d.location}",
                )
            }
        }
        appendLine()
        appendLine("描述符->端口 映射数: ${descriptorToPortMap().size}")
    }
}
