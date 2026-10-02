## PadDisplay v0.1.1

相对 v0.1.0 的修复与新功能。

### 修复

**1. `uid=-1` 的误导（你遇到的那个）**

UserService 返回的首行带 BOM（`\uFEFF`），客户端用 `startsWith("uid=")` 永远匹配不上，
于是显示成 `-1`，让人以为权限没拿到。**这只是显示 bug，不影响功能**，
但它掩盖了真正的报错信息。

现在改为直接问 Binder 拿身份（`Binder.getCallingUid()`），
并在 UserService 产出侧清掉 BOM / 零宽字符。

**2. 音频 AIDL transaction code 整体差 1**

`set/remove/getPreferredDevicesForStrategy` 由 `154/155/156` 修正为 **`153/154/155`**。
差 1 会让调用打到**相邻方法**上 —— transact 成功、不抛异常，但**什么都没做**。

**3. 窗口模式 AIDL code 修正**

`getWindowingMode` / `setWindowingMode`：
API 34 由 `95/96` 修正为 **`97/98`**，API 35/36 由 `96/97` 修正为 **`98/99`**。

> 这两处都是同一种错误的第二次出现（第一次在显示器侧）。
> 为此新增了 `tools/verify-aidl-codes.ps1`：用**真实 `aidl.exe`**
> 编译同序骨架 AIDL，读编译器写出的 `TRANSACTION_* = FIRST_CALL_TRANSACTION + n`，
> 一条命令复核全部 code。所有 code 现已逐条验证过。

### 新增

**音频输出选择** —— 直接针对「一线连显示器后蓝牙耳机没声」

- 枚举所有音频输出，标出「当前媒体输出」与「显示器类」设备
- **设为音频输出**：用 `IAudioService.setPreferredDevicesForStrategy`
  固定**媒体**音频输出（这是真正的解）；公共 API `AudioManager.setCommunicationDevice` 兜底
- **恢复自动**：一键清除
- 开关「接入外屏时自动把声音留在平板侧」（默认开启，优先耳机其次平板扬声器）

**音频状态卫生（不跨会话残留）**

音频的「首选设备」偏好写在系统 `AudioService` 策略状态里，**比 App 活得更久**，
卸载 App 不会自动清除。因此：

- App 每次启动先**自愈**：清除上次会话可能留下的音频固定
- **拔掉外接屏时自动清除**
- 只写 AOSP 首选设备偏好，**不碰音量 / force-use / 设备连接状态**
- 每次写入后**立刻读回验证**，不一致就报失败，绝不谎报成功

**外接屏显示模式：扩展 / 复制 / 仅外接屏**（对标 Windows Win+P）

| 模式 | 实现 |
|---|---|
| 扩展 | per-display `setWindowingMode(FULLSCREEN)` 打破镜像，读回验证 |
| 复制 | 如实说明 Android 14+ 已移除强制镜像 API，不冒险硬做 |
| 仅外接屏 | 关闭内屏 panel |

> 提示：**改分辨率前建议先切到「扩展」**。Android 外接屏默认可能是镜像，
> 镜像状态下内外屏共用 layer stack，外屏分辨率会改不动。

### 说明：关于音频，本项目源码里没有任何音频 API 调用

`AndroidManifest.xml` 里连一条 `uses-permission` 都没有。
「显示器抢走音频」是 Android 把 USB-C/DP 显示器当音频输出设备的**平台默认行为**；
而 `setForcedDisplaySize` 之类的显示配置变更会让系统重跑显示+音频策略重算，
从而把音频重新路由到显示器 —— 这是「用了显示器控制软件之后耳机才没声」的成因。

v0.1.1 的音频功能是**为了解决这个问题而新增**的能力，而不是原本就在动音频。

详见仓库 [README](https://github.com/alexhuangblankad/PadDisplay#readme)。
