package com.paddisplay.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.paddisplay.app.BuildConfig
import com.paddisplay.app.IPadDisplayService
import com.paddisplay.app.system.Reflect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 接入层（任务书第 10 节）。**不涉及 Root。**
 *
 * 权限链路：
 * ```
 * PadDisplay (app uid)
 *      │  Shizuku.requestPermission()
 *      ▼
 * Shizuku 服务 (运行在 shell 身份下)
 *      │  bindUserService()
 *      ▼
 * PadDisplayUserService  ← 本项目的权限中枢，uid=2000
 *      │  ServiceManager.getService() / SurfaceControl 反射
 *      ▼
 * Android 系统服务 (IDisplayManager / IWindowManager / SurfaceFlinger)
 * ```
 *
 * 状态机：[ConnectionState] 会依次经过
 * 未安装 → 未运行 → 未授权 → 已授权未绑定 → 已连接。
 * UI 直接订阅 [state]，用中文把当前卡在哪一步告诉用户。
 */
object ShizukuManager {

    private const val TAG = "PadDisplay/Shizuku"

    /** Shizuku 权限申请请求码。 */
    const val REQUEST_CODE_PERMISSION = 0x5A17

    /** Android 的 shell 用户 uid —— 也就是 Shizuku UserService 期望的身份。 */
    const val PROCESS_UID_SHELL = 2000

    /** UserService 进程名后缀。 */
    private const val USER_SERVICE_PROCESS_SUFFIX = "shizuku_service"

    /** 接口版本号：改 AIDL 时 +1，让 Shizuku 丢弃旧实例重新拉起。 */
    private const val USER_SERVICE_VERSION = 1

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    enum class Stage {
        /** 设备上没有安装 Shizuku。 */
        NOT_INSTALLED,

        /** 装了但服务没启动（用户没在 Shizuku App 里点“启动”）。 */
        NOT_RUNNING,

        /** 服务在跑，但本应用还没拿到授权。 */
        NOT_AUTHORIZED,

        /** 已授权，正在绑定 UserService。 */
        BINDING,

        /** 全部就绪，可以下发系统调用。 */
        CONNECTED,

        /** 绑定失败 / 运行期断开。 */
        ERROR,
    }

    data class ConnectionState(
        val stage: Stage,
        val message: String,
        val shizukuVersion: String = "",
        val shellUid: Int = -1,
        val serviceUid: Int = -1,
    ) {
        val canControl: Boolean get() = stage == Stage.CONNECTED
    }

    private val _state = MutableStateFlow(
        ConnectionState(Stage.NOT_RUNNING, "尚未检测 Shizuku 状态"),
    )
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** 已绑定的 UserService；未连接时为 null。 */
    @Volatile
    var service: IPadDisplayService? = null
        private set

    @Volatile
    private var appContext: Context? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun init(context: Context) {
        appContext = context.applicationContext
        addListeners()
        refresh()
    }

    private var listenersAdded = false

    private fun addListeners() {
        if (listenersAdded) return
        listenersAdded = true
        runCatching {
            // 授权结果回调（用户点了“允许”/“拒绝”之后触发）
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE_PERMISSION) {
                    Log.i(TAG, "权限申请结果: $grantResult")
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        bindUserService()
                    } else {
                        _state.value = ConnectionState(Stage.NOT_AUTHORIZED, "用户拒绝了 Shizuku 授权")
                    }
                }
            }
            // Shizuku 服务本身挂掉/重启
            Shizuku.addBinderReceivedListenerSticky {
                Log.i(TAG, "Shizuku binder 已就绪")
                refresh()
            }
            Shizuku.addBinderDeadListener {
                Log.w(TAG, "Shizuku binder 已断开")
                service = null
                _state.value = ConnectionState(Stage.NOT_RUNNING, "Shizuku 服务已停止，请重新启动 Shizuku")
            }
        }.onFailure { Log.w(TAG, "注册 Shizuku 监听失败: ${Reflect.describe(it)}") }
    }

    /** 重新检测并尽量推进到 CONNECTED。 */
    fun refresh() {
        val ctx = appContext
        if (ctx == null) {
            _state.value = ConnectionState(Stage.ERROR, "内部错误：ShizukuManager 未初始化")
            return
        }

        // 1) 是否安装
        if (!isShizukuInstalled(ctx)) {
            _state.value = ConnectionState(Stage.NOT_INSTALLED, "未检测到 Shizuku，请先安装 Shizuku")
            return
        }

        // 2) 服务是否在运行
        val binderAlive = runCatching {
            if (Shizuku.isPreV11()) false else Shizuku.pingBinder()
        }.getOrDefault(false)

        if (!binderAlive) {
            _state.value = ConnectionState(Stage.NOT_RUNNING, "Shizuku 未运行，请先打开 Shizuku 并启动服务")
            return
        }

        val version = runCatching { "v${Shizuku.getVersion()}" }.getOrDefault("未知")

        // 3) 是否已授权
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

        if (!granted) {
            _state.value = ConnectionState(
                Stage.NOT_AUTHORIZED,
                "Shizuku 已运行，但本应用尚未获得授权",
                shizukuVersion = version,
            )
            // 如果是“用户拒绝过”的状态，Shizuku 不会再弹窗，需要用户去 Shizuku 里手动授权
            if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
                Log.i(TAG, "需要用户手动在 Shizuku 中授权")
            }
            return
        }

        // 4) 已授权 -> 绑定 UserService
        if (service == null) {
            bindUserService()
        } else {
            _state.value = ConnectionState(
                Stage.CONNECTED,
                "Shizuku 已连接，系统控制权限已获得",
                shizukuVersion = version,
            )
        }
    }

    private fun isShizukuInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 授权
    // ------------------------------------------------------------------

    /** 弹出 Shizuku 授权对话框。 */
    fun requestPermission() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            _state.value = ConnectionState(Stage.NOT_RUNNING, "Shizuku 未运行，无法申请授权")
            return
        }
        runCatching {
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
            _state.value = ConnectionState(Stage.NOT_AUTHORIZED, "已发起授权申请，请在弹窗中允许")
        }.onFailure {
            _state.value = ConnectionState(Stage.ERROR, "发起授权失败: ${Reflect.describe(it)}")
        }
    }

    /** 打开 Shizuku 应用（未安装/未运行时引导用户）。 */
    fun openShizukuApp() {
        val ctx = appContext ?: return
        val intents = listOf(
            ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"),
            ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.redirect"),
        )
        val intent = intents.firstOrNull { it != null } ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    // ------------------------------------------------------------------
    // UserService
    // ------------------------------------------------------------------

    /** Shizuku UserService 的完整类名（Shizuku 会按 ComponentName 的 className 反射实例化）。 */
    private const val USER_SERVICE_CLASS = "com.paddisplay.app.system.PadDisplayUserService"

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, USER_SERVICE_CLASS),
        )
            .daemon(false)
            .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(USER_SERVICE_VERSION)
    }

    /**
     * 最近一次 UserService 自检的**原始文本**，直接显示在诊断信息里。
     * 不做任何解析，避免解析 bug 再掩盖真实错误。
     */
    private val _lastServiceReport = MutableStateFlow("")
    val lastServiceReport: StateFlow<String> = _lastServiceReport.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "UserService 已连接")
            val svc = binder?.let { IPadDisplayService.Stub.asInterface(it) }
            service = svc
            if (svc == null) {
                _state.value = ConnectionState(Stage.ERROR, "UserService 连接成功但接口转换失败")
                return
            }

            // 拿 shell 进程的 uid 做自检。
            //
            // ⚠️ 必须是 2000（shell）。但**不要**用 collectSystemInfo() 的文本去解析：
            // UserService 返回的第一行行首可能带 BOM(\uFEFF)，用 startsWith("uid=")
            // 会永远匹配不上，于是这里显示成 -1，让人误以为权限没拿到。
            // uid 就写在 service 进程的调用身份里，直接问 Binder 最可靠。
            val uid = runCatching { android.os.Binder.getCallingUid() }.getOrDefault(-1)

            // 同时把原始自检文本抓下来给诊断页用（失败也不影响连接状态）
            val raw = runCatching { svc.collectSystemInfo() }.getOrElse { "collectSystemInfo 失败: ${Reflect.describe(it)}" }
            _lastServiceReport.value = raw

            val isShell = uid == PROCESS_UID_SHELL
            _state.value = ConnectionState(
                Stage.CONNECTED,
                if (isShell) {
                    "Shizuku 已连接，系统控制权限已获得（UserService uid=2000 shell）"
                } else {
                    "UserService 已连接，但 uid=$uid（预期 2000/shell），系统调用可能被拒绝"
                },
                shizukuVersion = runCatching { "v${Shizuku.getVersion()}" }.getOrDefault(""),
                shellUid = PROCESS_UID_SHELL,
                serviceUid = uid,
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService 已断开")
            service = null
            _state.value = ConnectionState(Stage.ERROR, "UserService 连接已断开，请点击重试")
        }
    }

    fun bindUserService() {
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        if (!granted) {
            _state.value = ConnectionState(Stage.NOT_AUTHORIZED, "尚未授权，无法绑定 UserService")
            return
        }
        _state.value = ConnectionState(Stage.BINDING, "正在绑定 Shizuku UserService…")
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }.onFailure {
            Log.e(TAG, "绑定 UserService 失败: ${Reflect.describe(it)}")
            _state.value = ConnectionState(Stage.ERROR, "绑定 UserService 失败: ${Reflect.describe(it)}")
        }
    }

    fun unbindUserService() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        service = null
    }

    /** AIDL 接口描述符，用于诊断输出。 */
    fun aidlDescriptor(): String = IPadDisplayService::class.java.name
}
