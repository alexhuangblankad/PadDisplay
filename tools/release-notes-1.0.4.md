# PadDisplay v1.0.4

用户确认工作台能全屏 4K，但前端启动不行。本版对齐两处全屏启动路径，增加 Alt+Shift 临时全屏控件，并修正源码对照发现的 Settings 键错误。

- 修正 enable_freeform_support、force_desktop_mode_on_external_displays 等真实键；旧 development_ 前缀键的“已开启”和写入失败不能证明系统真实状态或权限。
- 修正“Settings 未保存相关值就证明 ROM 移除功能”的推断。
- 按 Taskbar 的新窗口启动思路避免复用内屏全屏任务；已有合理外屏自由窗口直接聚焦。singleTask / singleInstance 应用增加相应启动标志，仍以真实模式和边界读回为准。
- 窗口请求失败时显示真实设置与 feature，而不是继续把问题归因于 4K 边界。
- 主机启动不自动写全局设置，不修改厂商 ROM。仅用户主动使用原有高级设置的桌面模式按钮时才尝试写真实键。

- 前端全屏使用与工作台相同的 am start --display 路径；自由窗口失败时清理请求边界并请求该全屏路径，明确标为窗口失败，不能把全屏回退报作自由窗口成功。
- 所有全屏任务默认隐藏 Dock、三键及窗口控件；Alt+Shift 或 F9 临时显示 15 秒。Alt/Shift 的按下、抬起继续放行，单独左 Alt 不触发；需用户启用现有快捷键辅助服务。
- 保留侧边系统手势。临时面板获取焦点可影响串流指针捕获，Moonlight / UU 恢复行为需真机验证。黄色控件保留应用任务并返回桌面，不是系统任务最小化 API。

验证：release 构建、签名检查及 Alt+Shift / F9 / Alt 放行规则。没有可连接 OPPO，未确认设备真实键值，也不能宣称已解决 ColorOS 外屏自由窗口。

研究：[Taskbar 源码](https://github.com/farmerbb/Taskbar/blob/master/app/src/main/java/com/farmerbb/taskbar/util/U.java)、[AOSP Settings.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/provider/Settings.java)；完整差异见仓库 docs/freeform-research.md。