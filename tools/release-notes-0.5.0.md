v0.5.0: 桌面模式（DeX / 锤子 TNT 思路）—— 找到了能真正打开它的开关

## 你这个思路是对的，而且我之前漏了它

你提三星 DeX / 锤子 TNT，我去搜了，找到关键项目
[fox0001/android-desktop-mode](https://github.com/fox0001/android-desktop-mode)，
它的 README 直接点明了我们缺的那把钥匙：

> 从 Android 10 开始，Android 系统内置了"桌面模式"。
> **该模式下，App 可以自由拖动显示位置，并调整窗口大小（跟 PC 操作系统一样）。**
>
> 有趣的是，**"开发者选项"里开启了"模拟辅助显示设备"，就可以启用桌面模式**
>
> 进入"设置" → "系统" → "开发者选项"，勾选
> **"启用可自由调整的窗口"、"强制使用桌面模式"**。

## 关键发现：那两个勾就是两个 Global settings，Shizuku 能直接写

我在 AOSP `WindowManagerService` 里查到它们就是设置项：

```java
Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS
Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT
Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES
```

而且 WMS 的 `SettingsObserver` **实时监听**它们：

```java
if (mForceDesktopModeOnExternalDisplaysUri.equals(uri)) {
    updateForceDesktopModeOnExternalDisplays();   // 立即生效
}
void updateFreeformWindowManagement() {
    final boolean freeformWindowManagement =
        mContext.getPackageManager().hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        || Settings.Global.getInt(resolver, DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;
    mAtmService.mSupportsFreeformWindowManagement = freeformWindowManagement;   // ← 打开自由窗口
}
```

**写这两个键 = 帮你在开发者选项里打勾，不需要 root**（需要 `WRITE_SECURE_SETTINGS`，shell 持有）。

### 顺带解释了我之前那个"探测不存在"的疑点

我之前探测 `development_force_desktop_mode_on_external_displays` 得到 `-999`（键不存在），
当时判断为"本机没有这个设置"。现在明白了：

```java
Settings.Global.getInt(resolver, key, 0)   // 默认值 0，键不存在也返回 0
```

**键不存在是正常的** —— 它恰恰说明这个开关**从没被碰过**，
而不是"不能设置"。写入之后它就会存在。我之前的判断过于武断了。

## 本版新增「桌面模式」卡片（在主界面，不用进高级选项）

- **探测桌面模式能力（先看这个）**
  列出 5 个 Global settings 的当前值、系统 feature 能力、以及参考实现的做法
- **开启桌面模式** / **关闭**
  写入这两个键并**读回验证**

> 写入后通常需要**重新插拔外接屏或重启**才会完全生效。

## 但我必须把一件事说清楚（关于鼠标）

AOSP `WindowManagerService` 里那个字段的注释原文：

```java
/**
 * - Enable system decorations and IME on external screen.
 * - TODO: Show mouse pointer on external screen.     ← AOSP 自己还没做
 */
boolean mForceDesktopModeOnExternalDisplays;
```

**Android 官方在桌面模式里都还留着 "TODO: 在外接屏显示鼠标指针"。**

所以：

| 目标 | 桌面模式能否解决 |
|---|---|
| 外接屏是"真正的桌面"（自由窗口、可拖动缩放） | ✅ **能**，这正是它的用途 |
| 外接屏有系统装饰（状态栏/导航栏）、输入法 | ✅ 能 |
| **鼠标指针显示在外接屏** | ❌ AOSP 自己还是 TODO |
| **鼠标跨两块屏** | ❌ 需要 `DisplayTopology`，本机被关闭 |

**所以我不会告诉你"开了桌面模式鼠标就能跨屏了"—— 那是假的。**
但"要一个真正的桌面"，这个开关是对的方向。

## 请按这个顺序试

1. **探测桌面模式能力** → 把输出发我（看那几个键的值和能力）
2. 点 **开启桌面模式**
3. **重新插拔外接屏**（或重启）
4. 看外接屏上是不是变成了"桌面"—— App 能自由拖动、能调整窗口大小
5. 如果成了，再用「打开应用到外接屏」往里开应用

**如果第 4 步有效**，那就是重大进展：你得到了 DeX 式的桌面，
剩下的只是"用哪种方式操作它"（触控板 / 物理鼠标各自再议）。

**如果无效**，把探测输出发我 —— 我会看是哪一层挡住了：
键写入失败（ROM 限制）、还是 `config_isDesktopModeSupported` 为 false 导致自由窗口没真正打开。

## 说明

依然没有真机验证。本版是**根据 AOSP 源码 + 参考项目 README** 实现的最有依据的一步。

新增/修改：
- `IPadDisplayService.aidl`：probeDesktopMode / setDesktopMode
- `PadDisplayUserService.kt`：5 个 Global settings 的读写与读回验证
- `SystemDisplayService.kt` / `MainViewModel.kt`：对应封装
- `MainScreen.kt`：DesktopModeCard（主界面）
