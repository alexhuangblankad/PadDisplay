package com.paddisplay.app.system

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import java.util.Collections

/**
 * 隐藏 Binder（AIDL）接口调用层。
 *
 * ## transaction code 是怎么确定的（这一版做了修正）
 *
 * AIDL 的约定是 `TRANSACTION_x = FIRST_CALL_TRANSACTION + i`，
 * 其中 `i` 是方法在 `.aidl` 里的 **0 起始**声明序号。
 * 也就是说**第一个方法 = code 1**。
 *
 * 本项目最初把序号当成了 1 起始，导致所有 code 整体 +1，
 * 结果是「transact 成功、但调用到了完全不同的方法」——
 * 这种错误不会抛异常，只会静默地什么都不做，非常危险。
 *
 * 现在的取值是把 `tools/aidl/` 下的 AOSP 原版 `.aidl`
 * （android-14.0.0_r1 / android-15.0.0_r1 / android-16.0.0_r1）
 * 逐条解析出来的，并且用真实编译器验证过公式：
 * 对 4 个方法的测试接口，`aidl.exe` 生成的是
 * `TRANSACTION_methodA = FIRST_CALL_TRANSACTION + 0`。
 *
 * | 方法 | API 34 | API 35 | API 36 |
 * |---|---|---|---|
 * | IDisplayManager.getDisplayInfo | 1 | 1 | 1 |
 * | IDisplayManager.getDisplayIds | 2 | 2 | 2 |
 * | IDisplayManager.setUserPreferredDisplayMode | 42 | 42 | 42 |
 * | IDisplayManager.getUserPreferredDisplayMode | 43 | 43 | 43 |
 * | IDisplayManager.requestDisplayPower | — | 58 | 58 |
 * | IWindowManager.getInitialDisplaySize | 6 | 5 | 5 |
 * | IWindowManager.getBaseDisplaySize | 7 | 6 | 6 |
 * | IWindowManager.setForcedDisplaySize | 8 | 7 | 7 |
 * | IWindowManager.clearForcedDisplaySize | 9 | 8 | 8 |
 *
 * 注意 `requestDisplayPower` 在 API 35 是 `(int, boolean)`、API 36 是 `(int, int)`，
 * 但**两者 code 都是 58**（API 36 只是在其后追加了 `requestDisplayModes` → 59）。
 *
 * ## 为什么不再做「code 兜底扫描」
 *
 * 早先的版本在精确 code 失败时会从 1 扫到 80。
 * 这是**不安全**的：AIDL 不校验方法身份，扫描一旦命中 57 `disableConnectedDisplay`、
 * 36 `setBrightness` 之类的写方法，就会带着错位的参数真的执行副作用，
 * 而且因为没抛异常还会被判定为「成功」。
 *
 * 现在改为：**只发精确 code**，并在调用前用 [IBinder.getInterfaceDescriptor]
 * 校验 binder 身份。失败就如实上报失败，由上层切换别的通道。
 */
object AidlCodec {

    private const val TAG = "PadDisplay/Aidl"

    const val DESCRIPTOR_DISPLAY_MANAGER = "android.hardware.display.IDisplayManager"
    const val DESCRIPTOR_WINDOW_MANAGER = "android.view.IWindowManager"

    /** 每次调用的日志，输出到「诊断信息」里，是 ColorOS 排查的主要依据。 */
    val callLog: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun clearLog() = callLog.clear()

    // ------------------------------------------------------------------
    // 参数写入 / 返回值读取
    // ------------------------------------------------------------------

    fun writeArg(data: Parcel, arg: Any?) {
        when (arg) {
            null -> data.writeInt(0)
            is Int -> data.writeInt(arg)
            is Long -> data.writeLong(arg)
            is Boolean -> data.writeInt(if (arg) 1 else 0)
            is Float -> data.writeFloat(arg)
            is Double -> data.writeDouble(arg)
            is String -> data.writeString(arg)
            is IntArray -> data.writeIntArray(arg)
            is IBinder -> data.writeStrongBinder(arg)
            is Parcelable -> data.writeTypedObject(arg, 0)
            else -> throw IllegalArgumentException("不支持的 AIDL 参数类型: ${arg.javaClass.name}")
        }
    }

    /**
     * 写一个可空的 Parcelable（AIDL 的 `in Foo?`）。
     * AIDL 用 `writeTypedObject`：先写 1/0 的存在标记，再写内容。
     */
    fun writeNullableParcelable(data: Parcel, value: Parcelable?) {
        data.writeTypedObject(value, 0)
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /**
     * 用精确 transaction code 调用一个系统服务。
     *
     * @param descriptor 期望的接口描述符；用于校验 binder 身份，不符直接失败（不发送调用）
     */
    fun <T> call(
        binder: IBinder?,
        descriptor: String,
        label: String,
        code: Int,
        writeArgs: (Parcel) -> Unit,
        readReply: (Parcel) -> T,
    ): Result<T> {
        if (binder == null) {
            return fail(label, IllegalStateException("binder 为空"))
        }

        // 身份校验：防止把 display 服务当成 window 服务，或拿到错误的 binder。
        //
        // 审计 F12：早先在 `interfaceDescriptor` 抛异常时会**跳过校验继续调用**，
        // 那等于在最危险的情况下放弃唯一的防线。现在读取失败一律判失败，
        // 宁可报"取不到接口描述符"也不盲发 transact。
        val actual = try {
            binder.interfaceDescriptor
        } catch (t: Throwable) {
            return fail(label, IllegalStateException("无法读取 binder 描述符，拒绝调用: ${Reflect.describe(t)}"))
        }
        if (actual != descriptor) {
            return fail(
                label,
                IllegalStateException("binder 描述符不匹配：期望 $descriptor，实际 ${actual ?: "(null)"}"),
            )
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            writeArgs(data)
            if (!binder.transact(code, data, reply, 0)) {
                fail(label, IllegalStateException("transact($code) 返回 false"))
            } else {
                reply.readException()
                val value = readReply(reply)
                callLog.add("$label code=$code OK")
                Result.success(value)
            }
        } catch (t: Throwable) {
            fail(label, t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 无返回值的调用。 */
    fun callVoid(
        binder: IBinder?,
        descriptor: String,
        label: String,
        code: Int,
        writeArgs: (Parcel) -> Unit,
    ): Result<Unit> = call(
        binder = binder,
        descriptor = descriptor,
        label = label,
        code = code,
        writeArgs = writeArgs,
        readReply = { },
    )

    /** 读取 `out` 参数：AIDL 先写一个 1/0 的存在标记，再写内容。 */
    fun <T : Parcelable> readOutParcelable(reply: Parcel, creator: Parcelable.Creator<T>): T? {
        val present = reply.readInt()
        if (present == 0) return null
        return creator.createFromParcel(reply)
    }

    /** 校验 binder 是否是指定接口，用于诊断输出。 */
    fun describeBinder(binder: IBinder?): String {
        if (binder == null) return "(null)"
        val d = runCatching { binder.interfaceDescriptor }.getOrNull()
        return "${d ?: "(无描述符)"} alive=${binder.isBinderAlive}"
    }

    private fun <T> fail(label: String, t: Throwable): Result<T> {
        val msg = "$label 失败 ${Reflect.describe(t)}"
        Log.w(TAG, msg)
        callLog.add(msg)
        return Result.failure(t)
    }
}
