# VoyageOS 4 参考分析与 ColorOS 17 兼容预览

日期：2026-10-24。用户确认 PadDisplay 和 VoyageOS 4.0.1 在升级后的同一平板上均不能正常使用；具体系统拒绝原因尚无真机日志。不能把参考包当作已经可用的 ColorOS 17 实现。

## 对象和证据

用户提供文件 `VoyageOS4.0.1.apk`，aapt 读回 com.voyageos.app、versionName=4.0.0、versionCode=7、minSdk=31、targetSdk=36；SHA256 `D74711422B55D8709611301BA731918DBE07DE7781CFDD32C6DFCA7CC6DF9445`。
本地已有 `.gradle/voyage-4-offline` 静态分析缓存，本轮重新核对包体身份和可见源码机制。JADX 日志报告 211 个错误，不能视为完整源码。没有运行该 APK、访问它的服务、解密 Pro 模块或修改授权；分析缓存不进入 Git。

## 学习什么，保留什么

| 方向 | 可见机制/当前能力 | PadDisplay 处理 |
|---|---|---|
| 多启动通道 | 可见 ExternalDisplayActivity、defpackage/z00.java、r5.java 包含常规 ActivityOptions、特权/命令启动，以及对部分 ROM 落屏异常的后续处理 | 独立实现 shell 优先、普通应用回退，两条路径均以目标外屏桌面任务读回裁决；不照抄其混淆源码，不假设其 HyperOS 路径适用于 ColorOS |
| 输入设备固定归属 | server/InputDeviceBinder.java 仍是 descriptor/port 关联家族 | 保留 PadDisplay 原生设备关联、逐级读回；新增本机 IInputManager Stub 事务常量探测，API>36 无法确认时拒绝猜用 API36 号 |
| 会话/投屏 | server/DirectSession.java、app/cast、app/embed 等包含会话与虚拟显示路径 | 保留当前物理外屏产品，不用虚拟显示替代真实物理屏，也不采用 evdev 或鼠标事件转发 |
| 桌面组件 | app/ui/notificationcenter、ime、uhid 等代码表明存在相关组件 | 仅确认组件存在，不能断言所有功能可用或完整权限流程已解析。本轮不新增高权限通知读取、输入法或输入转发 |
| PadDisplay 已有能力 | 真实 Display Mode、外屏 DPI 读回/回滚、内屏电源、音频、一键还原、原生鼠标、首页单一导航底栏 | 全部保留；不是在证明 VoyageOS 缺少这些能力 |

## 本轮独立修改

- v1.1.0-rc1 / versionCode31，兼容预览，不标为稳定 ColorOS17 支持。
- 自有桌面 Activity 对 shell 的显式启动开放，以系统 DUMP 权限保护；普通第三方 App 不能直接启动此入口。本应用原有同 UID 调用仍可用。没有导出高权限 UserService。
- 从 Shizuku shell 请求启动目标外屏桌面，未确认则回退原普通 Activity 通道；命令不报错不计作成功。
- 只接受指定物理屏 ID 上的自有桌面任务、前台桌面、全屏 mode=1、非空边界的组合读回。拔插后身份变化会中止，不追随回收的屏 ID。此为系统任务状态证明，不是像素截图证明。
- 需要时仅请求自有桌面全屏；已是全屏时不再依赖 WindowContainerTransaction 写调用。没有改厂商的整屏窗口策略。
- 显示“正在确认外屏桌面”，失败则清理本次会话、恢复并保留启动失败/清理输出，避免假运行态。
- 完整诊断加入运行时 Android/API/系统 build、启动通道、最后任务读回、输入关联独立结果，沿用原高级诊断的复制入口；不增加首页调试按钮，不自动上传。

## 仍未解决的边界

没有可连接的 OPPO 真机，不能宣称 ColorOS17 黑屏已修复。两款应用均失败可能涉及系统策略/权限或接口变化，但还不能锁定根因。ColorOS17 名称不替代实际 Android SDK 检测。其他系统控制接口同样需要升级后实测；本轮未全面替换所有历史 Binder 适配。
原生连续跨屏、所有应用自由窗口、全局快捷键和串流捕获恢复没有新增真机成功证据。

## 依据与验收

Android 官方说明前台服务仍受后台 Activity 启动规则约束，不能把前台服务等同可见 Activity：https://developer.android.com/guide/components/activities/secure-bal
AOSP Shell 权限声明含 DUMP：https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/packages/Shell/AndroidManifest.xml

已执行 Release 构建、任务证据协议八项边界测试、原桌面/缩放回归、APK 签名核验。自有 Android35 模拟器安装、MainActivity 与 DUMP 保护桌面入口的 shell 启动返回 ok，AndroidRuntime 无本次崩溃；这不测试物理外屏，更不代替 ColorOS17。真机需要检查：开启主机、外屏桌面是否真实出现、输入归属、4K/DPI、Moonlight/UU、拔插重连和退出恢复。若仍失败，使用高级设置→诊断→完整诊断→复制，报告包含具体失败阶段；没有新建虚拟屏或改系统开关来绕过失败。
