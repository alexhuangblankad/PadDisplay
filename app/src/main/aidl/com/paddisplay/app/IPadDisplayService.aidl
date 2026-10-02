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

    /** 通过 IDisplayManager / DisplayManager 设置用户首选 Mode（真正切换硬件时序）。 */
    String setUserPreferredDisplayMode(int displayId, int modeId, int width, int height, float refreshRate);

    /** 重置用户首选 Mode 为系统默认。 */
    String resetUserPreferredDisplayMode(int displayId);

    /** 执行任意 shell 命令并返回 stdout+stderr（仅用于诊断）。 */
    String execCommand(String command);

    /** 兼容性探测：报告哪一个控制通道在本机可用。 */
    String probeCapabilities();

    /** 结束 UserService 进程。 */
    void destroy();
}
