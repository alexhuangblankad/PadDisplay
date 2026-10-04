# 物理外屏自由窗口对照研究（2026-10-04）

## 确认的本项目错误

旧代码将 Java 常量名误用为 Settings 数据库键。Android 16 源码中的映射是：

| 常量 | 实际键 |
|---|---|
| DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT | enable_freeform_support |
| DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS | force_desktop_mode_on_external_displays |
| DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES | force_resizable_activities |
| DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW | enable_non_resizable_multi_window |
| DEVELOPMENT_OVERRIDE_DESKTOP_EXPERIENCE_FEATURES | override_desktop_experience_features |

源码：[Settings.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/provider/Settings.java)。旧 development_ 前缀键的写入结果不能证明真实键的权限，读到 1 不能证明系统自由窗口已开启。历史诊断报告失效，并不证明当前 OPPO 真实键一定关闭。

[AOSP ActivityTaskManagerService.retrieveSettings](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java) 将 feature 声明或 enable_freeform_support 与通用多窗口条件组合后决定自由窗口能力。feature=false 单独不足以判断。Settings 数据库值也不能替代运行时模式读回。

## 开源项目实际使用的路径

1. [Taskbar U.java](https://github.com/farmerbb/Taskbar/blob/master/app/src/main/java/com/farmerbb/taskbar/util/U.java)：hasFreeformSupport 检查 feature 和真实设置键；通过 ActivityOptions 设置模式、目标显示器和边界。applyOpenInNewWindow 使用 MULTIPLE_TASK，对 singleTask / singleInstance 增加 LAUNCH_ADJACENT。还存在 InvisibleActivityFreeform 工作区初始化路径，未直接移植，不能假定适用于 ColorOS 16。
2. [FreeformShell ShellExecutor.kt](https://github.com/bravoyush/FreeformShell/blob/HEAD/app/src/main/java/com/example/freeformshell/ShellExecutor.kt)：尝试 setTaskWindowingMode，再回退 shell 命令；返回成功日志不等于任务模式已读回。现代 AOSP 的接口版本需要核验，不能直接复制旧反射签名或将 shell 写入成功当作功能成功。
3. [SecondScreen](https://github.com/farmerbb/SecondScreen)：主要改变分辨率 / DPI；README 明确仅面向 AOSP / Google experience，不保证厂商定制 ROM。不是解除 ColorOS 物理外屏窗口限制的证据。
4. [Android Display Extend](https://github.com/jqssun/android-display-extend)：物理与虚拟多显示管理均有，不能把虚拟显示上的能力当作 OPPO 物理外屏能力。

## v1.0.4 的独立修改

修正探测和用户手动桌面设置按钮的键名，纠正“没有已保存设置=ROM 移除功能”的错误推断。主机启动不自动写 Settings；原生鼠标绑定不依赖这些诊断键。

窗口请求采用新任务标志，避免沿用旧内屏全屏任务；目标屏已有尺寸合理的 mode=5 窗口则直接聚焦，避免重复创建。singleTask / singleInstance 可拒绝或复用；依然以读回为准。新任务可能改变应用的多实例行为，不关闭旧内屏任务、不强制应用变为可调整。

失败报告读取真实键与 feature，给出系统前置条件说明。分辨率、逻辑坐标、任务边界和应用兼容缩放仍分别验证。没有找到在 OPPO Pad Mini / ColorOS 16 上、无需修改厂商配置且允许所有物理外屏应用自由窗口的已验证公开方案。

没有可连接 OPPO，不能认定真实键当前值，也不能宣称这次修正已解决窗口问题。