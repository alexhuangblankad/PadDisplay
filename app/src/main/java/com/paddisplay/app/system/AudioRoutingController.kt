package com.paddisplay.app.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * 音频输出路由控制。
 *
 * ## 为什么要做这个（真实需求）
 *
 * Android 默认把 **USB-C / DisplayPort 显示器**当成一个音频输出设备。
 * 平板一线连上便携显示器之后，媒体音频会被自动切到显示器，
 * 用户连着的蓝牙耳机就「听不到声音」了。
 *
 * 更隐蔽的是：`IWindowManager.setForcedDisplaySize()` 之类的显示配置变更
 * 会让系统重新跑一遍显示 + 音频策略重算，于是**音频被重新路由到显示器**。
 * 这就是「用了显示器控制软件之后耳机才没声」的真实成因。
 *
 * ## 通道设计（v0.1.1 已按审计结论重构）
 *
 * | # | 通道 | 说明 |
 * |---|---|---|
 * | 1（首选） | 反射调用 `AudioManager.setPreferredDevicesForStrategy(AudioProductStrategy, List)` | 在 UserService 里运行，shell 持有 `MODIFY_AUDIO_ROUTING` |
 * | 2（兜底） | 手搓 `IAudioService` transaction | 仅在通道 1 不可用时使用 |
 * | 3（公共） | `AudioManager.setCommunicationDevice()` | 无需权限，但只能影响**通话音** |
 *
 * **为什么首选反射 `AudioManager` 而不是手搓 AIDL**（审计结论 F14）：
 * transaction code 会随 ROM 漂移，且手写 Parcel 编组极易出错
 * （本项目在 display 侧和 audio 侧各踩过一次「整体差 1」，
 * 症状都是 transact 成功但什么都没做）。直接调用 framework 自己的
 * `AudioManager` 隐藏方法，等于把 code 与编组交给 AOSP 自己保证。
 *
 * ## 安全约束（防止"卸载后声音还是坏的"）
 *
 * 音频的「首选设备」偏好写在系统 `AudioService` 的策略状态里，
 * **比 App 活得更久**，卸载 App 不会自动清除（重启才恢复）。
 * 因此：
 *
 * 1. 只写 AOSP 的**首选设备**偏好，**不碰音量、不碰 force-use、不改设备连接状态**。
 * 2. 每次写入后**立刻读回**确认，读回不一致就判定失败，绝不谎报成功。
 * 3. 提供 [unpinMediaOutput] 一键恢复；App 启动时会自愈式清除。
 * 4. 媒体目标**不再偷偷退化去改通话音**（那会造成"报成功但媒体没动"）。
 */
class AudioRoutingController(
    private val contextProvider: () -> Context?,
    private val audioServiceBinder: IBinder?,
) {

    companion object {
        const val DESCRIPTOR_AUDIO_SERVICE = "android.media.IAudioService"

        /** AOSP 默认 audio policy 配置里媒体策略排第一，仅作最后兜底。 */
        private const val STRATEGY_MEDIA_FALLBACK = 0

        /**
         * `IAudioService` 的 transaction code。
         *
         * 仅在通道 1（反射 AudioManager）不可用时才会用到。
         * 已用真实 `aidl.exe` 编译同序骨架验证（见 `tools/verify-aidl-codes.ps1`）：
         *
         * | 方法 | API 35 | API 36 |
         * |---|---|---|
         * | getAudioProductStrategies | 35 | 37 |
         * | setPreferredDevicesForStrategy | 144 | 153 |
         * | removePreferredDevicesForStrategy | 145 | 154 |
         * | getPreferredDevicesForStrategy | 146 | 155 |
         */
        private object Codes {
            fun getAudioProductStrategies(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 37 else 35
            fun setPreferredDevicesForStrategy(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 153 else 144
            fun removePreferredDevicesForStrategy(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 154 else 145
            fun getPreferredDevicesForStrategy(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 155 else 146
        }

        /**
         * 说明：这里**故意不提供** setCommunicationDevice / getCommunicationDevice 的 code。
         * 审计 F10 指出那两个值既写错又是死代码，而且 API 36 的
         * `setCommunicationDevice` 签名变成了 `(IBinder, int, AttributionSource)`，
         * 手搓 transaction 风险更高。通话音一律走公共 API
         * `AudioManager.setCommunicationDevice()`（无需权限，签名稳定）。
         */
        fun typeLabel(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "平板内置扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "平板内置扬声器(安全)"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙耳机(A2DP)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙耳机(SCO)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 耳机"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙 LE 音箱"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳麦"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件音频"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI / 显示器"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
            AudioDeviceInfo.TYPE_DOCK -> "底座"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "线路输出"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路输出"
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
            else -> "其他($type)"
        }

        /**
         * 是不是「显示器类」输出（默认会被系统抢着用的那些）。
         *
         * ⚠️ 这里**不包含 USB_HEADSET / USB_DEVICE**：USB-C 转 3.5mm 耳机
         * 和 USB 耳麦都是耳机，把它们当显示器会导致音频保护永远选不中它们。
         * 只有 HDMI/DP 与「USB 配件音频」才按显示器类处理。
         */
        fun isDisplayLike(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK,
            -> true
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    data class AudioOutput(
        val id: Int,
        val name: String,
        val type: Int,
        val typeName: String,
        val address: String,
        val isDisplayLike: Boolean,
        val isCurrentMedia: Boolean,
    ) {
        val displayName: String get() = if (name.isBlank()) typeName else "$typeName · $name"
    }

    data class Report(val ok: Boolean, val channel: String, val detail: String) {
        fun toText(): String = if (ok) "✅ $channel: $detail" else "❌ $channel: $detail"
    }

    private fun audioManager(): AudioManager? =
        contextProvider()?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 取 Parcelable 的 CREATOR（隐藏类只能反射）。 */
    private fun creatorOf(clazz: Class<*>?): Parcelable.Creator<*>? {
        if (clazz == null) return null
        return runCatching { clazz.getField("CREATOR").get(null) as? Parcelable.Creator<*> }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 读取（公共 API，无需权限）
    // ------------------------------------------------------------------

    /** 当前媒体音频"实际"走哪个输出设备（权威读法：按属性反查）。 */
    fun currentMediaOutput(): AudioDeviceInfo? {
        val am = audioManager() ?: return null
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                am.getAudioDevicesForAttributes(attrs).firstOrNull()
            } else {
                null
            }
        }.getOrNull()
    }

    /** 枚举所有可用的音频输出（sink）。 */
    fun availableOutputs(): List<AudioOutput> {
        val am = audioManager() ?: return emptyList()
        val current = currentMediaOutput()
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }
                .map { d ->
                    AudioOutput(
                        id = d.id,
                        name = d.productName?.toString() ?: "",
                        type = d.type,
                        typeName = typeLabel(d.type),
                        address = d.address ?: "",
                        isDisplayLike = isDisplayLike(d.type),
                        isCurrentMedia = current != null && current.id == d.id,
                    )
                }
                .sortedWith(compareBy({ it.isDisplayLike }, { it.typeName }))
        }.getOrDefault(emptyList())
    }

    /** 通话音当前走哪个设备（公共 API）。 */
    fun currentCommunicationOutput(): String {
        val am = audioManager() ?: return "(无 AudioManager)"
        return runCatching {
            val d = am.communicationDevice
            if (d == null) "(未指定)" else "${typeLabel(d.type)} id=${d.id}"
        }.getOrDefault("(读取失败)")
    }

    // ------------------------------------------------------------------
    // 通道 1：反射 AudioManager（首选）
    // ------------------------------------------------------------------

    /**
     * 构造 `AudioDeviceAttributes`。
     *
     * ⚠️ 这里有个必须避开的坑（审计发现）：`AudioDeviceAttributes(int type, String address)`
     * 的第一个参数是**原生设备类型**（`DEVICE_OUT_*`），不是 SDK 的 `AudioDeviceInfo.TYPE_*`。
     * 直接传 `AudioDeviceInfo.type` 会经过 native→SDK 映射：
     * 内置扬声器(2) 碰巧对上，但蓝牙 A2DP(8) 会变成「有线耳机」，HDMI(9)/USB(11) 变成 TYPE_UNKNOWN，
     * 结果是这些设备的首选设备设置被 `areAllDevicesSupported()` 拒绝 —— 功能静默失效。
     *
     * 正确做法是用 `AudioDeviceAttributes(AudioDeviceInfo)` 构造器（@SystemApi），
     * 它会把 role / type / address / name / nativeType 全部填对。
     */
    private fun buildDeviceAttributes(deviceId: Int): Any? {
        val am = audioManager() ?: return null
        val dev = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        }.getOrNull() ?: return null

        val clazz = Reflect.classForName("android.media.AudioDeviceAttributes") ?: return null

        // 首选：AudioDeviceAttributes(AudioDeviceInfo)
        runCatching {
            val ctor = clazz.getDeclaredConstructor(AudioDeviceInfo::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(dev)
        }

        // 兜底：老版本可能是 (int role, int type, String address)，role 2 = ROLE_OUTPUT
        runCatching {
            val ctor = clazz.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            ctor.isAccessible = true
            return ctor.newInstance(2, dev.type, dev.address ?: "")
        }

        AidlCodec.callLog.add("构造 AudioDeviceAttributes 失败（没有可用的构造器）")
        return null
    }

    /**
     * 取「媒体」音频策略对象。
     *
     * v0.1.1 修正（审计 F5）：
     * - `AudioProductStrategy` 里**没有** `AUDIO_STRATEGY_MEDIA` 这个 Java 常量，
     *   媒体策略 id 是 ROM audio policy 配置里的序号，不是固定值。
     * - 用 `getName() == "STRATEGY_MEDIA"` 匹配（最可靠），
     *   再用 `supportsAudioAttributes(media)` 兜底；
     *   两者都失败才退回常量 0，并**如实报告走的是哪条路**。
     */
    private fun findMediaStrategy(): Triple<Any?, Int, String> {
        val strategyClass = Reflect.classForName("android.media.audiopolicy.AudioProductStrategy")
            ?: return Triple(null, STRATEGY_MEDIA_FALLBACK, "固定值 0（找不到 AudioProductStrategy 类）")

        val list = runCatching {
            val m = Reflect.findMethod(strategyClass, "getAudioProductStrategies")
            m?.invoke(null) as? List<*>
        }.getOrNull()

        if (list.isNullOrEmpty()) {
            return Triple(null, STRATEGY_MEDIA_FALLBACK, "固定值 0（策略列表不可读）")
        }

        // 1) 按名字匹配：STRATEGY_MEDIA
        list.firstOrNull { s ->
            runCatching {
                (Reflect.findMethod(s!!.javaClass, "getName")?.invoke(s) as? String) == "STRATEGY_MEDIA"
            }.getOrDefault(false)
        }?.let { s ->
            val id = runCatching {
                Reflect.findMethod(s.javaClass, "getId")?.invoke(s) as? Int
            }.getOrNull() ?: STRATEGY_MEDIA_FALLBACK
            return Triple(s, id, "按名称匹配 STRATEGY_MEDIA（id=$id）")
        }

        // 2) 按属性支持匹配
        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        list.firstOrNull { s ->
            runCatching {
                val m = Reflect.findMethod(s!!.javaClass, "supportsAudioAttributes", AudioAttributes::class.java)
                m?.invoke(s, mediaAttrs) as? Boolean == true
            }.getOrDefault(false)
        }?.let { s ->
            val id = runCatching {
                Reflect.findMethod(s.javaClass, "getId")?.invoke(s) as? Int
            }.getOrNull() ?: STRATEGY_MEDIA_FALLBACK
            return Triple(s, id, "按 supportsAudioAttributes 匹配（id=$id）")
        }

        return Triple(null, STRATEGY_MEDIA_FALLBACK, "固定值 0（未匹配到媒体策略，共 ${list.size} 个策略）")
    }

    /** 用反射 AudioManager 固定媒体输出。 */
    private fun pinViaAudioManager(deviceId: Int, strategy: Any?): Report? {
        val channel = "AudioManager.setPreferredDevicesForStrategy"
        if (strategy == null) return null
        val am = audioManager() ?: return Report(false, channel, "没有 AudioManager")
        val attr = buildDeviceAttributes(deviceId)
            ?: return Report(false, channel, "构造 AudioDeviceAttributes 失败（deviceId=$deviceId）")

        return runCatching {
            val m = Reflect.findMethod(
                am.javaClass,
                "setPreferredDevicesForStrategy",
                strategy.javaClass,
                List::class.java,
            ) ?: return Report(false, channel, "AudioManager.setPreferredDevicesForStrategy 方法不存在")

            val ret = m.invoke(am, strategy, listOf(attr)) as? Int
            if (ret != null && ret != 0) {
                Report(false, channel, "系统返回错误码 $ret")
            } else {
                Report(true, channel, "已提交（deviceId=$deviceId）")
            }
        }.getOrElse { Report(false, channel, "调用失败 ${Reflect.describe(it)}") }
    }

    /** 用反射 AudioManager 读回首选设备。 */
    private fun readViaAudioManager(strategy: Any?): List<Any>? {
        if (strategy == null) return null
        val am = audioManager() ?: return null
        return runCatching {
            val m = Reflect.findMethod(
                am.javaClass,
                "getPreferredDevicesForStrategy",
                strategy.javaClass,
            ) ?: return null
            @Suppress("UNCHECKED_CAST")
            m.invoke(am, strategy) as? List<Any>
        }.getOrNull()
    }

    /** 用反射 AudioManager 清除首选设备。 */
    private fun unpinViaAudioManager(strategy: Any?): Report {
        val channel = "AudioManager.removePreferredDevicesForStrategy"
        if (strategy == null) return Report(false, channel, "拿不到媒体策略对象")
        val am = audioManager() ?: return Report(false, channel, "没有 AudioManager")
        return runCatching {
            val m = Reflect.findMethod(
                am.javaClass,
                "removePreferredDevicesForStrategy",
                strategy.javaClass,
            ) ?: return Report(false, channel, "方法不存在")
            val ret = m.invoke(am, strategy) as? Int
            if (ret != null && ret != 0) {
                Report(false, channel, "系统返回错误码 $ret")
            } else {
                Report(true, channel, "已清除媒体策略首选设备")
            }
        }.getOrElse { Report(false, channel, "调用失败 ${Reflect.describe(it)}") }
    }

    // ------------------------------------------------------------------
    // 通道 2：手搓 IAudioService（兜底）
    // ------------------------------------------------------------------

    /** 读 `List<AudioDeviceAttributes>`：AOSP 的 writeTypedList = [count] + count × [1][payload]。 */
    private fun readTypedDeviceList(reply: Parcel): List<Any> {
        val n = reply.readInt()
        if (n <= 0) return emptyList()
        val clazz = Reflect.classForName("android.media.AudioDeviceAttributes")
        val creator = creatorOf(clazz)
        val out = ArrayList<Any>(n)
        for (i in 0 until n) {
            val item = runCatching { creator?.createFromParcel(reply) }.getOrNull()
            if (item == null) break // 读不出来就别继续，避免把 Parcel 读歪
            out += item
        }
        return out
    }

    /** 读 `List<AudioProductStrategy>`（同样格式）。 */
    private fun readStrategyList(reply: Parcel): List<Any> {
        val n = reply.readInt()
        if (n <= 0) return emptyList()
        val clazz = Reflect.classForName("android.media.audiopolicy.AudioProductStrategy")
        val creator = creatorOf(clazz)
        val out = ArrayList<Any>(n)
        for (i in 0 until n) {
            val item = runCatching { creator?.createFromParcel(reply) }.getOrNull()
            if (item == null) break
            out += item
        }
        return out
    }

    private fun readPreferredViaAidl(strategyId: Int): List<Any>? = AidlCodec.call(
        binder = audioServiceBinder,
        descriptor = DESCRIPTOR_AUDIO_SERVICE,
        label = "getPreferredDevicesForStrategy($strategyId)",
        code = Codes.getPreferredDevicesForStrategy(),
        writeArgs = { it.writeInt(strategyId) },
        readReply = { readTypedDeviceList(it) },
    ).getOrNull()

    private fun pinViaAidl(deviceId: Int, strategyId: Int): Report {
        val channel = "IAudioService.setPreferredDevicesForStrategy"
        val attr = buildDeviceAttributes(deviceId)
            ?: return Report(false, channel, "构造 AudioDeviceAttributes 失败")
        val result = AidlCodec.call(
            binder = audioServiceBinder,
            descriptor = DESCRIPTOR_AUDIO_SERVICE,
            label = "setPreferredDevicesForStrategy($strategyId)",
            code = Codes.setPreferredDevicesForStrategy(),
            writeArgs = { data ->
                data.writeInt(strategyId)
                @Suppress("UNCHECKED_CAST")
                data.writeTypedList(listOf(attr) as List<Parcelable>)
            },
            readReply = { it.readInt() },
        )
        return result.fold(
            onSuccess = { ret ->
                if (ret != 0) Report(false, channel, "系统返回错误码 $ret") else Report(true, channel, "已提交（deviceId=$deviceId）")
            },
            onFailure = { Report(false, channel, "失败 ${Reflect.describe(it)}") },
        )
    }

    // ------------------------------------------------------------------
    // 通道 3：公共 API（通话音）
    // ------------------------------------------------------------------

    fun setCommunicationOutput(deviceId: Int): Report {
        val channel = "AudioManager.setCommunicationDevice"
        val am = audioManager() ?: return Report(false, channel, "没有 AudioManager")
        val device = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        }.getOrNull() ?: return Report(false, channel, "找不到 id=$deviceId 的输出设备")

        return runCatching {
            val ok = am.setCommunicationDevice(device)
            if (!ok) {
                Report(false, channel, "系统拒绝把通话音频切到「${typeLabel(device.type)}」")
            } else {
                val now = am.communicationDevice
                if (now != null && now.id == deviceId) {
                    Report(true, channel, "通话音频 -> ${typeLabel(device.type)} (id=$deviceId)")
                } else {
                    Report(false, channel, "写入后读回不一致（期望 id=$deviceId，实际 id=${now?.id}）")
                }
            }
        }.getOrElse { Report(false, channel, "调用失败 ${Reflect.describe(it)}") }
    }

    fun clearCommunicationOutput(): Report {
        val channel = "AudioManager.clearCommunicationDevice"
        val am = audioManager() ?: return Report(false, channel, "没有 AudioManager")
        return runCatching {
            am.clearCommunicationDevice()
            Report(true, channel, "已恢复通话音频自动路由")
        }.getOrElse { Report(false, channel, "调用失败 ${Reflect.describe(it)}") }
    }

    // ------------------------------------------------------------------
    // 对外主流程
    // ------------------------------------------------------------------

    /**
     * 把「媒体」音频固定到指定输出设备。**这是解决"一线连耳机没声"的主通道。**
     *
     * 返回的报告里第一条即为媒体通道的真实结果。
     * 注意：**媒体失败时不会偷偷去改通话音**（那会造成"报成功但媒体没动"的假象），
     * 通话音只有在 [alsoPinCommunication] 为 true 时才单独处理并单独报告。
     */
    fun pinMediaOutput(deviceId: Int, alsoPinCommunication: Boolean = false): List<Report> {
        val reports = mutableListOf<Report>()
        val (strategy, strategyId, how) = findMediaStrategy()

        // 通道 1：反射 AudioManager（首选）
        pinViaAudioManager(deviceId, strategy)?.let { reports += it }

        // 通道 2：手搓 AIDL（仅当通道 1 不可用）
        if (reports.isEmpty()) {
            if (audioServiceBinder == null) {
                reports += Report(false, "IAudioService.setPreferredDevicesForStrategy", "没有 AudioManager 也没有 Shizuku")
            } else {
                reports += pinViaAidl(deviceId, strategyId)
            }
        }

        // 读回验证 —— 无论走哪条通道，都以读回为准
        val back = readViaAudioManager(strategy) ?: readPreferredViaAidl(strategyId)
        val shown = back?.joinToString { describeDeviceAttrs(it) } ?: "(读回为空)"
        val matched = back?.any { deviceMatches(it, deviceId) } == true

        reports += if (matched) {
            Report(true, "读回验证", "媒体音频已固定到 id=$deviceId（$how）")
        } else {
            Report(
                false,
                "读回验证",
                "读回不一致：期望包含 id=$deviceId，实际 [$shown]。该设备可能不被系统接受为策略首选设备。",
            )
        }

        if (alsoPinCommunication) {
            reports += setCommunicationOutput(deviceId)
        }
        return reports
    }

    /** 清除媒体策略的首选设备，恢复系统自动路由。 */
    fun unpinMediaOutput(): List<Report> {
        val reports = mutableListOf<Report>()
        val (strategy, strategyId, _) = findMediaStrategy()

        val viaAm = unpinViaAudioManager(strategy)
        reports += viaAm

        val binder = audioServiceBinder
        if (!viaAm.ok && binder != null) {
            // 兜底：手搓 AIDL
            val r = AidlCodec.call(
                binder = binder,
                descriptor = DESCRIPTOR_AUDIO_SERVICE,
                label = "removePreferredDevicesForStrategy($strategyId)",
                code = Codes.removePreferredDevicesForStrategy(),
                writeArgs = { it.writeInt(strategyId) },
                readReply = { it.readInt() },
            )
            reports += r.fold(
                onSuccess = { ret ->
                    if (ret == 0) Report(true, "IAudioService.removePreferredDevicesForStrategy", "已清除")
                    else Report(false, "IAudioService.removePreferredDevicesForStrategy", "返回码 $ret")
                },
                onFailure = { Report(false, "IAudioService.removePreferredDevicesForStrategy", Reflect.describe(it)) },
            )
        }

        // 读回确认
        val back = readViaAudioManager(strategy) ?: readPreferredViaAidl(strategyId)
        reports += if (back.isNullOrEmpty()) {
            Report(true, "读回验证", "媒体策略已无首选设备（恢复自动路由）")
        } else {
            Report(false, "读回验证", "读回仍在：${back.joinToString { describeDeviceAttrs(it) }}")
        }

        reports += clearCommunicationOutput()
        return reports
    }

    private fun deviceMatches(attrs: Any, deviceId: Int): Boolean {
        val am = audioManager() ?: return false
        val target = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        }.getOrNull() ?: return false

        val type = runCatching {
            Reflect.findMethod(attrs.javaClass, "getType")?.invoke(attrs) as? Int
        }.getOrNull()
        val address = runCatching {
            Reflect.findMethod(attrs.javaClass, "getAddress")?.invoke(attrs) as? String
        }.getOrNull()

        // 类型必须匹配；地址在目标有地址时也要匹配（蓝牙要区分具体设备）
        if (type == null || type != target.type) return false
        val targetAddr = target.address ?: ""
        if (targetAddr.isNotEmpty() && (address ?: "") != targetAddr) return false
        return true
    }

    private fun describeDeviceAttrs(attrs: Any): String {
        val type = runCatching {
            Reflect.findMethod(attrs.javaClass, "getType")?.invoke(attrs) as? Int
        }.getOrNull() ?: -1
        val address = runCatching {
            Reflect.findMethod(attrs.javaClass, "getAddress")?.invoke(attrs) as? String
        }.getOrNull() ?: ""
        return "${typeLabel(type)}($address)"
    }

    /** 综合报告：当前路由状态 + 本应用设置的首选设备。 */
    fun describe(): String = buildString {
        appendLine("=== 音频输出环境 ===")
        appendLine("AudioManager: ${if (audioManager() != null) "可用" else "不可用"}")
        appendLine("IAudioService binder: ${AidlCodec.describeBinder(audioServiceBinder)}")
        val (strategy, strategyId, how) = findMediaStrategy()
        appendLine("媒体策略: id=$strategyId（$how）")
        appendLine("AudioManager.setPreferredDevicesForStrategy: " +
            (Reflect.findMethod(audioManager()?.javaClass, "setPreferredDevicesForStrategy",
                strategy?.javaClass ?: Any::class.java, List::class.java) != null))
        appendLine("当前媒体输出: " + (currentMediaOutput()?.let { "${typeLabel(it.type)} id=${it.id}" } ?: "(读不到)"))
        appendLine("当前通话输出: ${currentCommunicationOutput()}")
        val back = readViaAudioManager(strategy) ?: readPreferredViaAidl(strategyId)
        appendLine("已固定的媒体设备: " + (if (back.isNullOrEmpty()) "(无)" else back.joinToString { describeDeviceAttrs(it) }))
        appendLine("AudioDeviceAttributes 可构造: " + (buildDeviceAttributes(
            availableOutputs().firstOrNull()?.id ?: -1,
        ) != null))
        appendLine("可用输出设备:")
        availableOutputs().forEach { o ->
            appendLine(
                "  id=${o.id} ${o.displayName}" +
                    (if (o.isCurrentMedia) "  ← 当前媒体输出" else "") +
                    (if (o.isDisplayLike) "  [显示器类]" else ""),
            )
        }
    }
}
