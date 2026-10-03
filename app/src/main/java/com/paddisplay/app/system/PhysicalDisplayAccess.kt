package com.paddisplay.app.system

import android.os.Build
import android.os.IBinder

/**
 * 物理屏 token 的统一获取入口。
 *
 * ## 为什么需要这一层
 *
 * Android 14 起，`android.view.SurfaceControl` 上的这几个方法被移走了：
 * `getPhysicalDisplayIds()` / `getPhysicalDisplayToken(long)` /
 * `getInternalDisplayToken()`。新家是
 * `com.android.server.display.DisplayControl`（在 `services.jar` 里，
 * 依赖本地库 `android_servers`）。
 *
 * 所以单个渠道不够，这里按顺序尝试：
 * 1. `SurfaceControl`（Android 10~13 可用；部分 ROM 保留了兼容壳）
 * 2. `DisplayControl`（Android 14+，参考 DisplayToggleExtreme 的加载方式）
 *
 * 全程**不需要 Root**，但必须在 shell 权限进程里调用才有意义
 * （普通 App 进程会被 SELinux 挡住）。
 */
object PhysicalDisplayAccess {

    private const val SURFACE_CONTROL = "android.view.SurfaceControl"
    private const val DISPLAY_CONTROL = "com.android.server.display.DisplayControl"

    @Volatile
    private var displayControlClass: Class<*>? = null

    @Volatile
    private var displayControlTried = false

    /** 实际生效的渠道名，用于诊断输出。 */
    @Volatile
    var activeChannel: String = "(未探测)"
        private set

    // ------------------------------------------------------------------
    // DisplayControl 按需加载
    // ------------------------------------------------------------------

    fun loadDisplayControl(): Class<*>? {
        displayControlClass?.let { return it }
        if (displayControlTried) return null
        displayControlTried = true

        // 1) 直接加载（少数 ROM 仍可从 boot classpath 看到）
        Reflect.classForName(DISPLAY_CONTROL)?.let {
            displayControlClass = it
            return it
        }

        // 2) Android 14+：从 services.jar 建独立 ClassLoader
        return try {
            val factory = Reflect.classForName("com.android.internal.os.ClassLoaderFactory")
                ?: return null
            val create = Reflect.findMethod(
                factory,
                "createClassLoader",
                String::class.java,
                String::class.java,
                String::class.java,
                ClassLoader::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
            ) ?: return null

            val loader = create.invoke(
                null,
                "/system/framework/services.jar",
                null,
                null,
                ClassLoader.getSystemClassLoader(),
                0,
                true,
                null,
            ) as? ClassLoader ?: return null

            val clazz = loader.loadClass(DISPLAY_CONTROL)

            // 必须显式加载本地库，否则 native 方法会 UnsatisfiedLinkError
            runCatching {
                val loadLibrary0 = Runtime::class.java.getDeclaredMethod(
                    "loadLibrary0",
                    Class::class.java,
                    String::class.java,
                )
                loadLibrary0.isAccessible = true
                loadLibrary0.invoke(Runtime.getRuntime(), clazz, "android_servers")
            }.onFailure {
                AidlCodec.callLog.add("loadLibrary0(android_servers) 失败 ${Reflect.describe(it)}")
            }

            displayControlClass = clazz
            AidlCodec.callLog.add("已通过 services.jar 加载 $DISPLAY_CONTROL")
            clazz
        } catch (t: Throwable) {
            AidlCodec.callLog.add("加载 $DISPLAY_CONTROL 失败 ${Reflect.describe(t)}")
            null
        }
    }

    private fun displayControlCall(method: String, vararg args: Any?): Any? {
        val clazz = loadDisplayControl() ?: return null
        val types = args.map { arg ->
            when (arg) {
                is Long -> Long::class.javaPrimitiveType
                is Int -> Int::class.javaPrimitiveType
                else -> arg?.javaClass
            }
        }.toTypedArray()
        val m = Reflect.findMethod(clazz, method, *types) ?: return null
        return runCatching { m.invoke(null, *args) }.getOrElse {
            AidlCodec.callLog.add("DisplayControl.$method 失败 ${Reflect.describe(it)}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 公开接口
    // ------------------------------------------------------------------

    fun physicalDisplayIds(): LongArray? {
        Reflect.classForName(SURFACE_CONTROL)?.let { clazz ->
            Reflect.findMethod(clazz, "getPhysicalDisplayIds")?.let { m ->
                runCatching {
                    (m.invoke(null) as? LongArray)?.let {
                        activeChannel = "SurfaceControl.getPhysicalDisplayIds"
                        return it
                    }
                }
            }
        }
        return (displayControlCall("getPhysicalDisplayIds") as? LongArray)?.also {
            activeChannel = "DisplayControl.getPhysicalDisplayIds"
        }
    }

    fun physicalDisplayToken(physicalId: Long): IBinder? {
        Reflect.classForName(SURFACE_CONTROL)?.let { clazz ->
            Reflect.findMethod(clazz, "getPhysicalDisplayToken", Long::class.javaPrimitiveType)?.let { m ->
                runCatching {
                    (m.invoke(null, physicalId) as? IBinder)?.let {
                        activeChannel = "SurfaceControl.getPhysicalDisplayToken"
                        return it
                    }
                }
            }
        }
        return (displayControlCall("getPhysicalDisplayToken", physicalId) as? IBinder)?.also {
            activeChannel = "DisplayControl.getPhysicalDisplayToken"
        }
    }

    /**
     * 内屏 token。
     * - Android 10~13：`SurfaceControl.getInternalDisplayToken()`（更早是 `getBuiltInDisplay(0)`）
     * - Android 14+：`getPhysicalDisplayToken(端口为 0 的物理屏 id)`
     */
    fun internalDisplayToken(): IBinder? {
        Reflect.classForName(SURFACE_CONTROL)?.let { clazz ->
            Reflect.findMethod(clazz, "getInternalDisplayToken")?.let { m ->
                runCatching {
                    (m.invoke(null) as? IBinder)?.let {
                        activeChannel = "SurfaceControl.getInternalDisplayToken"
                        return it
                    }
                }
            }
            Reflect.findMethod(clazz, "getBuiltInDisplay", Int::class.javaPrimitiveType)?.let { m ->
                runCatching {
                    (m.invoke(null, 0) as? IBinder)?.let {
                        activeChannel = "SurfaceControl.getBuiltInDisplay"
                        return it
                    }
                }
            }
        }
        val ids = physicalDisplayIds() ?: return null
        // 物理屏 id 低 8 位是端口号，内屏是 0
        val internalId = ids.firstOrNull { (it and 0xFFL).toInt() == 0 } ?: ids.firstOrNull() ?: return null
        return physicalDisplayToken(internalId)
    }

    /** 从隐藏的 `Display.getAddress()`（形如 `local:<physicalId>`）取物理屏 id。 */
    fun physicalIdOfPhysicalAddress(address: String?): Long? {
        if (address == null) return null
        val raw = address.substringAfter("local:", "")
        if (raw.isEmpty() || raw == address) return null
        return raw.toLongOrNull()
    }

    /**
     * 取 `IAudioService` 的 Binder。
     * 用于给「媒体」音频策略固定首选输出设备（需要 `MODIFY_AUDIO_ROUTING`，shell 持有）。
     * 服务名是 `"audio"`（见 `android.media.AudioManager` / `AudioService`）。
     */
    fun audioServiceBinder(): IBinder? {
        Reflect.classForName("android.os.ServiceManager")?.let { clazz ->
            Reflect.findMethod(clazz, "getService", String::class.java)?.let { m ->
                runCatching { return m.invoke(null, "audio") as? IBinder }
            }
        }
        return null
    }

    /**
     * 取 `IInputManager` 的 Binder。
     * 用于把输入设备（触摸/鼠标/键盘）路由到外接屏 ——
     * 这是「扩展模式能用」的前提：Android 默认不把输入绑到外接屏。
     * 服务名是 `"input"`。
     */
    fun inputManagerBinder(): IBinder? {
        Reflect.classForName("android.os.ServiceManager")?.let { clazz ->
            Reflect.findMethod(clazz, "getService", String::class.java)?.let { m ->
                runCatching { return m.invoke(null, "input") as? IBinder }
            }
        }
        return null
    }

    fun describe(): String = buildString {
        val sc = Reflect.classForName(SURFACE_CONTROL)
        appendLine("SurfaceControl.getPhysicalDisplayIds: ${Reflect.findMethod(sc, "getPhysicalDisplayIds") != null}")
        appendLine("SurfaceControl.getPhysicalDisplayToken: ${Reflect.findMethod(sc, "getPhysicalDisplayToken", Long::class.javaPrimitiveType) != null}")
        appendLine("SurfaceControl.getInternalDisplayToken: ${Reflect.findMethod(sc, "getInternalDisplayToken") != null}")
        appendLine("SurfaceControl.setDisplayPowerMode: ${Reflect.findMethod(sc, "setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType) != null}")
        appendLine("DisplayControl(services.jar): ${if (loadDisplayControl() != null) "可用" else "不可用"}")
        appendLine("生效渠道: $activeChannel")
        appendLine("物理屏 id: ${physicalDisplayIds()?.joinToString() ?: "(null)"}")
        appendLine("内屏 token: ${if (internalDisplayToken() != null) "可用" else "不可用"}")
        appendLine("SDK: ${Build.VERSION.SDK_INT}")
    }
}
