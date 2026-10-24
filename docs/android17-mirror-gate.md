# Android 17 镜像与外屏任务门槛（v1.1.0-rc2）

2026-10-24 用户 rc1 报告：外屏仍为内屏复制。报告确认 API 37 原生鼠标关联读回成功，但 shell 和应用启动外屏桌面都被 SafeActivityOptions 拒绝，外屏桌面任务不存在。因此输入权限与桌面启动权限必须分开判断。原始报告含设备标识，不入库。

AOSP Android 17 的 ActivityTaskSupervisor 在相关显示内容管理开关开启时，先检查 Display.canHostTasks()；不允许承载任务的显示器即使 shell 启动也会被拒绝。DisplayManagerService 根据当前用户 secure 设置 mirror_built_in_display 更新这一能力。SecondaryDisplayPolicy 在设备不满足桌面模式条件时可强制恢复镜像。用户报告尚未包含 canHostTasks 和镜像偏好值，因此这只是待真机验证的根因候选。

参考源码：
- https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java
- https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java
- https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/wm/SecondaryDisplayPolicy.java

rc2 在 API 37+ 读取 canHostTasks、镜像偏好、用户与两项桌面能力资源。仅当 canHostTasks=false、镜像明确为 1、显示器声明内容模式切换、会话身份一致且仅接入一块物理外屏时，尝试将标准用户镜像偏好设为 0。它是用户级偏好，会影响该用户外屏策略，因此多外屏时不自动修改。没有写厂商属性、桌面实验 flag 或强制自由窗口能力。

修改前持久化原值，shell 进程也保留恢复副本；读回设置与 canHostTasks 后才继续启动，最终仍要求外屏桌面任务可见。失败、退出、进程死亡和重连恢复原镜像偏好；恢复失败保留记录，下次启动先重试。无法读取或系统强制改回时明确失败，不宣称独立显示成功。准备阶段提示已去掉虚假的成功结论。

验证范围：本地构建和纯 JVM 恢复逻辑验证；尚无 ColorOS 17 真机成功证据。自由窗口、跨屏连续鼠标空间不在本次修复结果中。
