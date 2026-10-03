// PadDisplay UserService 接口
//
// 该接口的实现运行在 Shizuku 拉起的 shell(uid=2000) 权限进程中，
// 因此可以调用 IWindowManager / IDisplayManager / SurfaceControl 等隐藏 API。
//
// 注意：所有方法都返回 String（除 destroy 外），
// 以便在 ColorOS 上出现异常时把完整错误信息回传到 UI 的“诊断信息”页面。
package com.paddisplay.app;

interface IPadDisplayService {

    /** 连通性与权限自检，人类可读文本。 */
    String collectSystemInfo();

    /** dumpsys display 原始输出（可能很大）。 */
    String dumpDisplay();

    /** 枚举物理显示器：id、token 可用性、是否内屏。 */
    String listPhysicalDisplays();

    /**
     * 单独设置某块屏幕的电源模式。
     * @param displayId 逻辑 displayId
     * @param powerMode 0=OFF, 1=DOZE, 2=NORMAL, 3=DOZE_SUSPEND, 4=ON_SUSPEND
     */
    String setDisplayPowerMode(int displayId, int powerMode);

    /** 读取当前电源模式（如可读）。 */
    String getDisplayPowerMode(int displayId);

    /** 通过 IWindowManager.setForcedDisplaySize 做 per-display 逻辑尺寸覆盖。 */
    String setForcedDisplaySize(int displayId, int width, int height);

    /** 清除 per-display 逻辑尺寸覆盖。 */
    String clearForcedDisplaySize(int displayId);

    /** 通过 IWindowManager 读取 base / initial / override 尺寸。 */
    String getDisplaySizes(int displayId);

    /**
     * 读回某个显示器的真实 Mode 状态，用于**验证** Mode 切换是否真的生效。
     *
     * 这是必需的：`setUserPreferredDisplayMode` 调用返回成功只代表"没报错"，
     * 不代表分辨率真的变了。必须读回当前实际 Mode 才能判定。
     */
    String getModeState(int displayId);

    /**
     * 直接执行 shell 命令（带绝对路径兜底），用于诊断 `wm` / `dumpsys` 是否可用。
     */
    String probeShellEnvironment();

    /**
     * 镜像状态探针 —— 判断外接屏是否**仍在被镜像**。
     *
     * 为什么必须单独探：
     * `windowingMode == FULLSCREEN` **不能**说明没在镜像。
     * 是否镜像由 `DisplayContent.shouldBeMirrored()` 决定，主要看
     * ①`DisplayManagerService.shouldForceDesktopMode()`（读全局设置
     *   `development_force_desktop_mode_on_external_displays` 与设备的
     *   `config_isDesktopModeSupported`）
     * ②`DisplayWindowSettings.isDisplayEnabled()`。
     *
     * 镜像时外接屏会跟着内屏的模式走，所以分辨率设不上、还会出现黑边。
     */
    String probeMirrorState(int externalDisplayId);

    /**
     * 关闭「在外接屏强制桌面模式」这一全局设置。
     *
     * 这是 AOSP 让外接屏被强制镜像的条件之一：
     * `DisplayManagerService.shouldForceDesktopMode()` 同时要求
     * ①设备支持桌面模式（`config_isDesktopModeSupported`）
     * ②`Settings.Global.development_force_desktop_mode_on_external_displays == 1`
     *
     * 关闭它之后，外接屏在 FULLSCREEN 下不再被强制镜像，
     * 才有可能独立设置 4K 分辨率、消除黑边。
     *
     * 需要 `WRITE_SECURE_SETTINGS`（Shizuku 的 shell 持有）。
     * @param enable true = 打开（恢复系统原状），false = 关闭
     */
    String setForceDesktopMode(boolean enable);

    /**
     * 在外接屏上真实启动一个 Activity —— **决定性的独立渲染测试**。
     *
     * 这是系统自带的 `am start --display <id>`，完全绕开本应用的任何实现。
     * 如果外接屏上能出现一个填满 4K 的独立窗口，说明「扩展」在系统层面是成立的、
     * 黑边来自上层的镜像/投屏；如果仍然带黑边，说明 ColorOS 的多屏服务
     * 在更上层接管了外接屏。
     *
     * @param displayId 目标显示器
     * @param component 要启动的组件（如 com.android.settings/.Settings）
     */
    String launchOnDisplay(int displayId, String component);

    /** 找出 ColorOS 的多屏 / 投屏 / 外接显示相关设置页入口。 */
    String findDisplaySettingsActivities();

    // ------------------------------------------------------------------
    // 输入路由（让扩展模式真正可操作）
    //
    // Android 默认不把输入设备绑定到外接屏：内屏触摸只对内屏生效、
    // 鼠标被限制在默认屏内。所以「扩展」能渲染但操作不了。
    // 这些方法把外接输入设备（触摸/鼠标/键盘）关联到指定显示器。
    // ------------------------------------------------------------------

    /** 列出输入设备及其是否外接。 */
    String listInputDevices();

    /**
     * 把外接输入设备全部绑定到指定显示器。
     * 三级回退：按描述符关联 uniqueId → 按端口关联 uniqueId → 按端口关联物理端口。
     */
    String bindInputToDisplay(int displayId);

    /** 解除所有输入关联，恢复默认（输入跟随默认屏）。 */
    String clearInputAssociations();

    // ------------------------------------------------------------------
    // 多屏拓扑（屏幕左右关系）—— Android 版的「Windows 显示布局」
    //
    // AOSP 在 feature flag 打开时会把拓扑喂给输入系统
    // （DisplayManagerService → mInputManagerInternal.setDisplayTopology），
    // 这正是光标/输入能够跨屏的机制。
    // flag 关闭时服务端 setDisplayTopology 是静默空操作，所以必须读回验证。
    // ------------------------------------------------------------------

    /** 探测本机是否支持多屏拓扑。 */
    String probeDisplayTopology();

    /**
     * 设置两块屏的相对位置。
     * @param primaryDisplayId 作为坐标原点(0,0)的显示器
     * @param otherDisplayId 另一块
     * @param position 0=左 1=上 2=右 3=下（与 AOSP TreeNode.POSITION_* 一致）
     */
    String setDisplayTopologyLayout(
        int primaryDisplayId,
        int otherDisplayId,
        int position,
        int primaryWidth,
        int primaryHeight,
        int otherWidth,
        int otherHeight
    );

    /**
     * 把某个应用（或系统设置）启动到指定桌面。
     * @param displayId 目标显示器
     * @param component 可为空（空则启动该包的主 Activity）
     * @param packageName 当 component 为空时使用
     */
    String launchAppOnDisplay(int displayId, String component, String packageName);

    // ------------------------------------------------------------------
    // 坐标空间（解决「光标显示位置与实际点击位置不一致」）
    //
    // 参考项目 AdaptiveScreenPlus 的实测结论：
    //   Display.getRealSize()/getMetrics() 会被本应用的「兼容缩放」污染
    //   （内屏实际 1920x1080 被报成 1496x1242，导致右侧 424px 够不到）。
    // 唯一可靠的注入坐标空间是 `dumpsys window displays` 里那块屏的 cur=WxH，
    // 它与 `input -d N` / screencap 是同一套坐标系。
    // ------------------------------------------------------------------

    /** 对照列出各屏的「注入坐标空间」，用于定位光标错位。 */
    String probeCoordinateSpaces(String displayIdsCsv);

    /** 在指定屏的坐标空间里注入一次点击（自动夹取到有效范围）。 */
    String injectTapOnDisplay(int displayId, int x, int y);

    /**
     * 一键还原本应用可能改动过的**所有**系统状态。
     *
     * 覆盖：
     * - 解除所有输入设备关联（只读，不做危险改动）
     * - 清除音频输出固定（媒体策略首选设备 + 通话音）
     * - 还原显示拓扑到之前快照
     * - 清除逻辑尺寸覆盖与用户首选 Mode
     * - 内屏窗口模式还原为 FULLSCREEN，并打开内屏电源
     */
    String restoreAll();

    // ------------------------------------------------------------------
    // 应用启动器（外接屏的「开始菜单」）
    //
    // 早先只写死了「设置」一个组件，所以用户只能在外屏开设置。
    // 正确做法：解析每个应用的 launcher Activity 组件，再用
    // `am start --display N -n <组件>` 启动到目标屏。
    // ------------------------------------------------------------------

    /** 列出所有可启动的应用：包名|标签|组件。 */
    String listLaunchableApps();

    /** 把指定包名的主 Activity 启动到目标屏。 */
    String launchPackageOnDisplay(int displayId, String packageName);

    /** 通过 IDisplayManager / DisplayManager 设置用户首选 Mode（真正切换硬件时序）。 */
    String setUserPreferredDisplayMode(int displayId, int modeId, int width, int height, float refreshRate);

    /** 重置用户首选 Mode 为系统默认。 */
    String resetUserPreferredDisplayMode(int displayId);

    /** 执行任意 shell 命令并返回 stdout+stderr（仅用于诊断）。 */
    String execCommand(String command);

    /** 兼容性探测：报告哪一个控制通道在本机可用。 */
    String probeCapabilities();

    // ------------------------------------------------------------------
    // 音频输出路由
    //
    // 为什么需要它：Android 默认把 USB-C / DP 显示器当作音频输出设备，
    // 一线连之后媒体音频会被切到显示器，用户的蓝牙耳机就「没声」了。
    // 正确做法是给「媒体」音频策略指定首选设备（IAudioService）。
    // ------------------------------------------------------------------

    /** 枚举所有可用的音频输出设备。 */
    String listAudioOutputs();

    /** 当前媒体音频实际走哪个设备（读 AOSP 状态，不是猜的）。 */
    String getCurrentAudioRouting();

    /**
     * 指定音频输出设备。
     * @param deviceId AudioDeviceInfo.getId()
     * @param pinMedia   是否固定「媒体」策略
     * @param pinComm    是否固定「通话音」策略
     */
    String setAudioOutputDevice(int deviceId, boolean pinMedia, boolean pinComm);

    /** 清除本应用设置的所有首选音频设备（恢复系统自动路由）。 */
    String clearAudioOutputPreference();

    // ------------------------------------------------------------------
    // 外接屏显示模式（扩展 / 复制 / 仅外接屏）
    // ------------------------------------------------------------------

    /**
     * 把外接屏切成独立屏幕（= 扩展模式）。
     *
     * 实现是 per-display 地把它的 windowingMode 设为 FULLSCREEN，
     * 写入后读回验证。只影响 externalDisplayId，不碰内屏。
     */
    String setExtendMode(int externalDisplayId);

    /** 读取外接屏当前的 windowingMode 与显示模式相关状态。 */
    String getDisplayModeState(int externalDisplayId);

    /** 结束 UserService 进程。 */
    void destroy();
}
