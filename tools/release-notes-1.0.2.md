# PadDisplay v1.0.2

用户实测桌面布局已正常，但外屏应用不能自由调整，只有关闭可用。本版针对任务窗口请求修正代码；不宣称已通过 ColorOS 真机验证。

- 即使启动任务报告 mode=5，也明确设置初始窗口边界，避免沿用旧的全屏边界。
- 自由窗口切换同时验证模式和边界，不能仅凭模式值判成功。
- 窗口事务失败或读回不符时，通过系统 startActivityFromRecents + ActivityOptions 再次请求指定任务的显示器、窗口模式和边界。
- 窗口页显示操作结果、任务 ID、实际模式与完整边界，失败时保留事务错误与系统 freeform 能力声明。
- 保留原生鼠标、显示、DPI、任务关闭与已有前端；不修改厂商配置、不强制应用可调整属性、不模拟应用窗口。

验证：release 构建与签名检查。物理外屏上的窗口恢复、移动、缩放和多应用同时显示需 OPPO 真机确认；没有可连接真机，不将构建成功当作窗口成功。

系统接口依据：[AOSP IActivityTaskManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/IActivityTaskManager.aidl) 与 [WindowOrganizerController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/wm/WindowOrganizerController.java)。