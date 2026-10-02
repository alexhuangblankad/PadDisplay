package com.paddisplay.app.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * 音频输出路由控制。
 *
 * ## 为什么要做这个（真实需求，不是 scope creep）
 *
 * Android 的音频策略默认把 **USB-C / DisplayPort 显示器**当成一个音频输出设备。
 * 平板通过 Type-C 一线连上便携显示器之后，媒体音频会被自动切到显示器，
 * 用户连着的蓝牙耳机就「听不到声音」了。
 *
 * 更隐蔽的是：`IWindowManager.setForcedDisplaySize()` 之类的显示配置变更
 * 会让系统重新跑一遍显示 + 音频策略重算，于是**音频被重新路由到显示器**。
 * 这就是「用了显示器控制软件之后耳机才没声」的真实成因。
 *
 * ## 两条通道
 *
 * | # | 通道 | 权限 | 能力 |
 * |---|---|---|---|
 * | 1 | `AudioManager.setCommunicationDevice()` | 无（公共 API） | 只能影响**通话音** |
 * | 2 | `IAudioService.setPreferredDevicesForStrategy()` | `MODIFY_AUDIO_ROUTING`（shell 持有） | 能固定**媒体音频**输出 ← 真正的解 |
 *
 * 通道 2 必须走 Shizuku 的 shell 进程。`IAudioService` 的 transaction code
 * 同样是从 AOSP `IAudioService.aidl` 逐条解析得出的（方法序号 + 1）。
 *
 * ## 安全约束（非常关键）
 *
 * 这个功能会**改变系统级音频路由状态**。为了避免"卸载后声音还是坏的"，
 * 遵守以下规则：
 *
 * 1. 只写 AOSP 的**首选设备**偏好（`setPreferredDevicesForStrategy`），
 *    不碰音量、不碰 force-use、不改设备连接状态。
 * 2. 每次写入后**立刻读回**（`getPreferredDevicesForStrategy`）确认，
 *    读回不一致就判定失败，绝不谎报成功。
 * 3. 提供 [clear] 一键恢复系统自动路由。
 * 4. 相关设置持久化在 App 私有 DataStore 里，卸载即消失，
 *    不留任何跨应用残留状态。
 */
class AudioRoutingController(
    private val contextProvider: () -> Context?,
    private val audioServiceBinder: IBinder?,
) {

    companion object {
        /**
         * AOSP `IAudioService.aidl` 解析出的确定 transaction code。
         *
         * ⚠️ 这些值由 `tools/aidl/IAudioService.api35/36.aidl` 逐条解析得出，
         * 公式是 AIDL 的 `FIRST_CALL_TRANSACTION + 方法序号(0起始)`。
         * 每个调用都会先校验 `IBinder.getInterfaceDescriptor()`，
         * 并在写入后读回验证，所以即使某个 ROM 的方法表不同也不会造成静默错误。
         */
        private object Codes {
            fun getAudioProductStrategies(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 37 else 35

            /** 权威值：API 36 = 153（已用真实 aidl.exe 编译骨架验证）。 */
            fun setPreferredDevicesForStrategy(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 153 else 143

            /** 权威值：API 36 = 154。 */
            fun removePreferredDevicesForStrategy(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 154 else 144

            /** 权威值：API 36 = 155。 */
            fun getPreferredDevicesForStrategy(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 155 else 145

            fun setCommunicationDevice(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 186 else 177
            fun getCommunicationDevice(): Int = if (android.os.Build.VERSION.SDK_INT >= 36) 187 else 178
        }

        const val DESCRIPTOR_AUDIO_SERVICE = "android.media.IAudioService"

        /**
         * 音频策略 id。
         * `AudioProductStrategy` 里有 `AUDIO_STRATEGY_MEDIA = 0`，
         * 这是 AOSP 固定值（`frameworks/base/media/java/android/media/audiofx/...`），
         * 另外还有一条动态查找的路径作为保险。
         */
        private const val STRATEGY_MEDIA_FALLBACK = 0

        /** 把 AudioDeviceInfo 的类型转成中文，便于 UI 展示。 */
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

        /** 显示器类音频输出（我们要避免默认走这里）。 */
        fun isDisplayLike(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
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
        /** 当前媒体音频是否正走这个设备 */
        val isCurrentMedia: Boolean,
    ) {
        val displayName: String get() = if (name.isBlank()) typeName else "$typeName · $name"
    }

    data class Report(val ok: Boolean, val channel: String, val detail: String) {
        fun toText(): String = if (ok) "✅ $channel: $detail" else "❌ $channel: $detail"
    }

    // ------------------------------------------------------------------
    // 读取（公共 API，无需权限）
    // ------------------------------------------------------------------

    /** 取 Parcelable 的 CREATOR（隐藏类只能反射）。 */
    private fun creatorOf(clazz: Class<*>?): Parcelable.Creator<*>? {
        if (clazz == null) return null
        return runCatching {
            clazz.getField("CREATOR").get(null) as? Parcelable.Creator<*>
        }.getOrNull()
    }

    private fun audioManager(): AudioManager? =
        contextProvider()?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 当前媒体音频"实际"走哪个输出设备（权威读法：AudioManager 按属性反查）。 */
    fun currentMediaOutput(): AudioDeviceInfo? {
        val am = audioManager() ?: return null
        return runCatching {
            // getAudioDevicesForAttributes(AudioAttributes) 是公共 API（API 33+），
            // 返回系统为「媒体」这类属性实际选中的设备。
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
    // 通道 1：公共 API（通话音）
    // ------------------------------------------------------------------

    /**
     * 用公共 API 指定通话音输出设备。
     * `AudioManager.setCommunicationDevice()` 不需要任何权限。
     */
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
                // 读回验证
                val now = am.communicationDevice
                if (now != null && now.id == deviceId) {
                    Report(true, channel, "通话音频 -> ${typeLabel(device.type)} (id=$deviceId)")
                } else {
                    Report(false, channel, "写入后读回不一致（期望 id=$deviceId，实际 id=${now?.id}）")
                }
            }
        }.getOrElse { Report(false, channel, "调用失败 ${Reflect.describe(it)}") }
    }

    /** 用公共 API 清除通话音偏好。 */
    fun clearCommunicationOutput(): Report {
        val channel = "AudioManager.clearCommunicationDevice"
        val am = audioManager() ?: return Report(false, channel, "没有 AudioManager")
        return runCatching {
            am.clearCommunicationDevice()
            Report(true, channel, "已恢复通话音频自动路由")
        }.getOrElse { Report(false, channel, "调用失败 ${Reflect.describe(it)}") }
    }

    // ------------------------------------------------------------------
    // 通道 2：IAudioService（媒体音频，需要 shell 权限）
    // ------------------------------------------------------------------

    /**
     * 找到「媒体」音频策略的 id。
     * 先试 AOSP 固定值 0，再用 `getAudioProductStrategies()` 动态核对。
     */
    fun findMediaStrategyId(): Pair<Int, String> {
        val binder = audioServiceBinder
            ?: return STRATEGY_MEDIA_FALLBACK to "固定值 0（取不到 IAudioService）"

        val strategies = AidlCodec.call(
            binder = binder,
            descriptor = DESCRIPTOR_AUDIO_SERVICE,
            label = "getAudioProductStrategies",
            code = Codes.getAudioProductStrategies(),
            writeArgs = { },
            readReply = { reply -> readStrategyList(reply) },
        ).getOrNull()

        if (strategies.isNullOrEmpty()) {
            return STRATEGY_MEDIA_FALLBACK to "固定值 0（策略列表不可读）"
        }

        // 逐个策略比对它的 audio attributes 是否等于「媒体」
        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        strategies.forEach { (strategyId, attrs) ->
            if (attrs != null && attributesMatchMedia(attrs, mediaAttrs)) {
                return strategyId to "动态查找（策略 $strategyId）"
            }
        }
        return STRATEGY_MEDIA_FALLBACK to "固定值 0（未找到匹配属性，按 AOSP 常量）"
    }

    private fun attributesMatchMedia(attrs: AudioAttributes, media: AudioAttributes): Boolean {
        // AudioAttributes 有公共 getter，直接比 usage / contentType
        return runCatching {
            attrs.usage == media.usage && attrs.contentType == media.contentType
        }.getOrDefault(false)
    }

    /** 读 `List<AudioProductStrategy>`：先读 List 存在标记，再逐项读 Parcelable。 */
    private fun readStrategyList(reply: Parcel): List<Pair<Int, AudioAttributes?>> {
        val present = reply.readInt()
        if (present == 0) return emptyList()
        val n = reply.readInt()
        val out = ArrayList<Pair<Int, AudioAttributes?>>(n)
        val strategyClass = Reflect.classForName("android.media.audiopolicy.AudioProductStrategy")
        for (i in 0 until n) {
            val strategy = strategyClass?.let { clazz ->
                val creator = creatorOf(clazz)
                runCatching { creator?.createFromParcel(reply) }.getOrNull()
            }
            val id = strategy?.let { s ->
                runCatching {
                    val m = Reflect.findMethod(s.javaClass, "getId")
                    m?.invoke(s) as? Int
                }.getOrNull()
            } ?: -1
            val attrs = strategy?.let { s ->
                runCatching {
                    val m = Reflect.findMethod(s.javaClass, "getAudioAttributes")
                    m?.invoke(s) as? AudioAttributes
                }.getOrNull()
            }
            out += id to attrs
        }
        return out
    }

    /** 构造 `AudioDeviceAttributes`（隐藏类，需要反射）。 */
    private fun buildDeviceAttributes(deviceId: Int): Any? {
        val am = audioManager() ?: return null
        val dev = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        }.getOrNull() ?: return null

        val clazz = Reflect.classForName("android.media.AudioDeviceAttributes") ?: return null
        // 构造器：AudioDeviceAttributes(int type, String address) 或 (int role, int type, String address)
        val ctor2 = runCatching {
            clazz.getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
        }.getOrNull()
        ctor2?.let {
            runCatching { return it.newInstance(dev.type, dev.address ?: "") }
        }
        val ctor3 = runCatching {
            clazz.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
        }.getOrNull()
        ctor3?.let {
            // AUDIO_DEVICE_ROLE_OUTPUT = 2（AudioDeviceAttributes.ROLE_OUTPUT）
            runCatching { return it.newInstance(2, dev.type, dev.address ?: "") }
        }
        return null
    }

    /**
     * 把「媒体」音频固定到指定输出设备。
     * 这是真正解决"一线连之后耳机没声"的通道。
     */
    fun pinMediaOutput(deviceId: Int): List<Report> {
        val reports = mutableListOf<Report>()
        val channel = "IAudioService.setPreferredDevicesForStrategy"

        if (audioServiceBinder == null) {
            reports += Report(false, channel, "取不到 IAudioService（需要 Shizuku；普通 App 无法修改媒体路由）")
            return reports
        }

        val attr = buildDeviceAttributes(deviceId)
        if (attr == null) {
            reports += Report(false, channel, "构造 AudioDeviceAttributes 失败（deviceId=$deviceId）")
            return reports
        }

        val (strategy, how) = findMediaStrategyId()
        val devices = listOf(attr)

        val result = AidlCodec.call(
            binder = audioServiceBinder,
            descriptor = DESCRIPTOR_AUDIO_SERVICE,
            label = "setPreferredDevicesForStrategy($strategy)",
            code = Codes.setPreferredDevicesForStrategy(),
            writeArgs = { data ->
                data.writeInt(strategy)
                // in List<AudioDeviceAttributes> -> writeTypedList
                @Suppress("UNCHECKED_CAST")
                data.writeTypedList(devices as List<Parcelable>)
            },
            readReply = { it.readInt() },
        )

        result.fold(
            onSuccess = { ret ->
                if (ret != 0) {
                    reports += Report(false, channel, "系统返回错误码 $ret（策略 $strategy，$how）")
                } else {
                    // 关键：写入后读回验证，避免"报成功其实没生效"
                    val back = readPreferredDevices(strategy)
                    val matched = back?.any { deviceMatches(it, deviceId) } == true
                    val shown = back?.joinToString { describeDeviceAttrs(it) } ?: "(读回为空)"
                    reports += if (matched) {
                        Report(true, channel, "媒体音频已固定到 id=$deviceId（策略 $strategy，$how）")
                    } else {
                        Report(
                            false,
                            channel,
                            "写入返回成功但读回不一致：期望包含 id=$deviceId，实际 [$shown]。" +
                                "本机可能用了不同的策略表。",
                        )
                    }
                }
            },
            onFailure = {
                reports += Report(false, channel, "失败 ${Reflect.describe(it)}（策略 $strategy，$how）")
            },
        )

        // 媒体固定失败时，退而求其次用公共 API 固定通话音频
        if (reports.none { it.ok }) {
            reports += setCommunicationOutput(deviceId)
        }
        return reports
    }

    private fun readPreferredDevices(strategy: Int): List<Any>? = AidlCodec.call(
        binder = audioServiceBinder,
        descriptor = DESCRIPTOR_AUDIO_SERVICE,
        label = "getPreferredDevicesForStrategy($strategy)",
        code = Codes.getPreferredDevicesForStrategy(),
        writeArgs = { it.writeInt(strategy) },
        readReply = { reply ->
            val present = reply.readInt()
            if (present == 0) {
                emptyList()
            } else {
                val n = reply.readInt()
                val clazz = Reflect.classForName("android.media.AudioDeviceAttributes")
                val creator = creatorOf(clazz)
                val list = ArrayList<Any>(n)
                for (i in 0 until n) {
                    val item = runCatching { creator?.createFromParcel(reply) }.getOrNull()
                    if (item == null) {
                        // 读不出来就没法继续，避免把 Parcel 读歪
                        break
                    }
                    list += item
                }
                list
            }
        },
    ).getOrNull()

    private fun deviceMatches(attrs: Any, deviceId: Int): Boolean {
        // AudioDeviceAttributes 没有公共 id，用 type+address 与 AudioDeviceInfo 对齐
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
        return type == target.type && (address ?: "") == (target.address ?: "")
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

    /** 清除媒体策略的首选设备，恢复系统自动路由。 */
    fun unpinMediaOutput(): List<Report> {
        val reports = mutableListOf<Report>()
        val channel = "IAudioService.removePreferredDevicesForStrategy"
        if (audioServiceBinder == null) {
            reports += Report(false, channel, "取不到 IAudioService")
            return reports
        }
        val (strategy, _) = findMediaStrategyId()
        val result = AidlCodec.call(
            binder = audioServiceBinder,
            descriptor = DESCRIPTOR_AUDIO_SERVICE,
            label = "removePreferredDevicesForStrategy($strategy)",
            code = Codes.removePreferredDevicesForStrategy(),
            writeArgs = { it.writeInt(strategy) },
            readReply = { it.readInt() },
        )
        reports += result.fold(
            onSuccess = { ret ->
                val back = readPreferredDevices(strategy)
                if (ret == 0 && back.isNullOrEmpty()) {
                    Report(true, channel, "已清除媒体音频固定（策略 $strategy），恢复系统自动路由")
                } else {
                    Report(false, channel, "返回码 $ret，读回仍在: ${back?.joinToString { describeDeviceAttrs(it) } ?: "(空)"}")
                }
            },
            onFailure = { Report(false, channel, "失败 ${Reflect.describe(it)}") },
        )
        reports += clearCommunicationOutput()
        return reports
    }

    /** 综合报告：当前路由状态 + 本应用设置的首选设备。 */
    fun describe(): String = buildString {
        appendLine("=== 音频输出环境 ===")
        appendLine("IAudioService binder: ${AidlCodec.describeBinder(audioServiceBinder)}")
        val (strategy, how) = findMediaStrategyId()
        appendLine("媒体策略 id: $strategy（$how）")
        appendLine("当前媒体输出: ${currentMediaOutput()?.let { "${typeLabel(it.type)} id=${it.id}" } ?: "(读不到)"}")
        appendLine("当前通话输出: ${currentCommunicationOutput()}")
        val back = readPreferredDevices(strategy)
        appendLine(
            "已固定的媒体设备: " +
                (if (back.isNullOrEmpty()) "(无)" else back.joinToString { describeDeviceAttrs(it) }),
        )
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
