# VoyageOS 包体分析与 PadDisplay v0.6.3

分析对象是用户提供的本地 APK；未运行 APK、未访问其后端、未修改付费或授权校验。

## 包体身份

- 文件名：VoyageOS3.5.1.apk.1。
- Manifest：com.voyageos.app，versionName=3.5.0，versionCode=6，minSdk=31，targetSdk=36。
- SHA256：26785738D95E3766AA3998768A42F5AD53282151CE86C0677DA22730F567247B。
- 反编译输出保留在 .gradle/voyage-analysis/decompiled，不进入 Git。
- JADX 处理 5223 项，报告 173 个错误；输出可用于定位调用机制，不能视为完整原始源码。

## 已找到的机制

| 机制 | 本地证据 | 判断边界 |
|---|---|---|
| 原生输入设备固定关联 | com/voyageos/server/InputDeviceBinder.java 的 bind、probe、associate | 使用 IInputManager 的 descriptor/port uniqueId association；这与 PadDisplay 的静态关联方向一致，不是连续跨屏拓扑 |
| 输入生命周期释放 | CoreDisplayService.java 的 inputTokenDeath、bindInputDevices | App Binder 死亡时解绑，值得借鉴，但本轮 PadDisplay 尚未实现此机制 |
| 原始设备事件读取与抓取 | EvdevReader.java 的 scanLoop、nativeGrab、reportMouse | 扫描 /dev/input，鼠标坐标钳制在目标屏范围，通过 Binder 回调上送；权限、SELinux 与 Moonlight 行为不能由静态代码证明 |
| 事件注入 | Injector.java 的 hostMouse、hostScroll、inject | 构造鼠标 MotionEvent，指定目标显示器后注入；不等同硬件原生 pointer capture |
| 应用显示会话 | DirectSession.java 的 VirtualDisplay、attachSurface、resize，以及 defpackage/pg.java 的 createVirtualDisplay 调用 | 包内存在虚拟显示器及 Surface 会话；不意味着所有主机模式都走此通道 |
| 显示镜像路径 | defpackage/wp1.java 的 VirtualDisplayConfig.Builder、setDisplayIdToMirror | 至少存在将显示器内容交给 Surface 的镜像实现，不能把这种镜像和独立物理扩展屏混为一谈 |

用户补充实测：VoyageOS 不能完成内外屏鼠标迁移，但可以把鼠标固定到外屏，并以平板应用列表启动外屏应用。因此当前目标已收敛到原生外屏鼠标与主机模式，不再要求边缘跨屏。

反编译源码未找到可靠的 DisplayTopology 调用；受反编译失败和 native 未完整分析限制，这不是绝对不存在的证明。

## 本轮独立实现

没有将 VoyageOS 的反编译代码、资源、原生库或授权模块加入 PadDisplay。

- 主机模式入口：先独立外屏、保持内屏点亮，再进行鼠标静态关联读回，展开应用列表。
- 应用搜索：按名称与包名搜索，取消之前只显示前 40 个应用的限制。
- 物理外屏 DPI 缩放：75%、100%、125%、150%、200%；100% 对应物理默认 DPI。
- DPI 读回验证、确认倒计时、超时回滚；保存最初 override，应用重启后仍可恢复修改前缩放。
- 修改与回滚前重新枚举目标屏，避免外屏 ID 变化后把 DPI 写到其它屏。
- 一键还原同时尝试恢复原 DPI；系统还原不再无条件返回成功，失败拓扑恢复不删除快照。
- 一键还原中的分辨率恢复仅处理物理外屏，避免顺带清除用户内屏设置。

## 未实现与待测

- 没有仿制 VoyageOS 的完整桌面、任务栏、多虚拟显示器窗口管理系统。
- 本版本外屏 DPI 调整不承诺修改其它 App 自建虚拟显示器内部的 DPI。
- 不读取 evdev、不自绘鼠标、不新增事件中转。外屏光标与 Moonlight pointer capture 需要 OPPO 真机验证。
- 主机模式绑定失败会保留具体结果；桌面模式全局开关仍由已有功能单独控制。
- DPI 改动可能在重启后仍保留，不能以重启替代恢复按钮。

## 真机验收

本地已通过 release 编译，以及 DisplayDensitySmoke 的六项边界检查：异步更新读回、reset、拒绝写入不报成功、读取失败、内屏保护、DPI 范围保护。它们不替代以下真机验收。

1. 安装 release，重新连接 Shizuku，并确认系统桌面模式处于先前已可用的状态。
2. 保持鼠标、物理外屏连接，开始主机模式，检查绑定读回和外屏光标。
3. 搜索并启动普通 App、Moonlight，分别验证鼠标、右键、滚轮与相对模式。
4. 试 125% 与 150%，确认文字变大；检查硬件 Mode 是否仍为 4K@60。
5. 修改后不确认，验证自动恢复；再确认一次，重启 PadDisplay 后恢复修改前缩放。
6. 缩放确认期间拔插外屏，确保出现明确失败提示而不修改内屏。
7. 一键还原，保存全部失败项与 DPI 恢复结果。
