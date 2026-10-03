# PadDisplay 交接文档

> **交接目的**：接手者需要判断"在 Shizuku（shell 权限）之下，能否用软件层实现
> 物理鼠标在平板内屏与外接显示器之间的原生指针流转"。
>
> **本文档所有断言的来源**：AOSP 源码（android-16.0.0_r1 / main）、真实 `aidl.exe`
> 编译验证、用户真机实测日志。**凡未验证的一律标注"未验证"。**

---

## 0. 一句话现状

PadDisplay **已实现**外接屏识别、4K 分辨率与刷新率控制、真实 Display Mode 切换、
内屏独立关屏与自动恢复、音频输出路由、应用启动到外接屏、一键开始/还原。

**唯一未达成**：鼠标光标在平板与外接屏之间的流转。用户目标是
**"一个物理鼠标从平板滑到外接屏"**（等同 Windows / 三星 DeX）。

**核心结论（有源码证据）**：需要 `display_topology` 这个 **read-only aconfig flag**，
ColorOS 编译时关闭，**运行时不可改**。但**输入设备关联**（`addUniqueIdAssociation*`）
只受一个 **shell 持有的 signature 权限**保护，**这条路尚未实测**。

---

## 1. 项目基本信息

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/alexhuangblankad/PadDisplay （public，分支 main） |
| 本地路径 | `D:\deepseekharness\oppodisplay` |
| 当前版本 | **v0.6.1**（versionCode 19） |
| 最新 commit | `78f750d docs: 鼠标跨屏需求广泛调研` |
| applicationId | `com.paddisplay.app` |
| SDK | compileSdk 36 / minSdk 26 / targetSdk 34 |
| 语言配置 | Java 17, Kotlin 2.2.21, Compose Material3, AGP 8.13.1 |
| 签名 | release 用 `keystore/paddisplay-release.jks`，alias `paddisplay`，密码 `paddisplay2024`（**公开仓库中已暴露，属已知不安全项**） |
| 构建 | `$env:ANDROID_HOME="D:\android-sdk"; .\gradlew.bat :app:assembleRelease --console=plain` |
| 发布 | `gh release create vX.Y.Z <apk> --title ... --notes-file tools/release-notes-X.Y.Z.md` |
| Release 历史 | v0.1.0 → v0.6.1，共 11 个 release |

### 交付约定

- **只出 release APK，不出 debug**（用户明确要求）
- 中文 README / release notes
- 提交信息用 `git commit -F tools/commit-msg.txt`

---

## 2. 用户设备（真机实测确认，非推测）

| 项 | 实测值 |
|---|---|
| 设备 | **OPPO Pad Mini** |
| SoC | 高通 Snapdragon 8 Gen 5（Oryon v3，Adreno 829） |
| 内存/存储 | 12GB / 256GB |
| 内屏 | 8.8" AMOLED，**2520×1680**（3:2），144Hz，displayId 0 |
| 系统 | **Android 16（SDK 36）/ ColorOS** |
| 接口 | USB-C 3.1 + **DisplayPort 输出**（DP Alt Mode 可用） |
| 外接显示器 | OPD2515 "HDMI 屏幕"，**3840×2160 @ 60Hz**，30 个 supportedModes |
| 外接屏 displayId | 13 → 后续重枚举为 15 / 20 / 4（**会变，勿硬编码**） |
| Shizuku | 已安装可用，UserService 以 **uid=2000** 运行 |

### 已实测确认的关键系统事实

| 事实 | 值 | 来源 |
|---|---|---|
| `config_isDesktopModeSupported` | **false** | 真机资源探测 |
| `display_topology` 能力探测 | **supported=false**（系统返回 null） | 真机 API 调用 |
| `settings put global development_force_desktop_mode_on_external_displays 1` | **写入失败** | 真机 v0.6.0 日志 |
| `am start --display <id> -n <组件>` | **成功**，外接屏满屏 4K | 用户实测 |
| 外接屏窗口模式 | `windowingMode=FULLSCREEN(1)` | dumpsys |
| 鼠标绑定到外接屏的尝试 | 曾报"无可绑定设备" | **该失败是我的 bug，非权限问题**，见 §5 |

---

## 3. 技术机制全貌（三段，必须分清）

用户提出的关键问题是：**"这只是接口被官方屏蔽，而不是没有接口，
在 Shizuku 授权之下还不能在软件层面解决吗？"**

答案需要分三段回答——**一段能，一段不能，一段不确定**。

### 3.1 【能做】输入设备关联到显示器（Input Device → Display Association）

**这是把物理鼠标的输入送到外接屏的机制，且 shell 有权限。**

AOSP `InputManagerService.java`（android-16.0.0_r1）:

```java
@Override // Binder call
public void addUniqueIdAssociationByDescriptor(@NonNull String inputDeviceDescriptor,
                                               @NonNull String displayUniqueId) {
    if (!checkCallingPermission(
            android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY,
            "addUniqueIdAssociationByDescriptor()")) {
        throw new SecurityException(
                "Requires ASSOCIATE_INPUT_DEVICE_TO_DISPLAY permission");
    }
    ...
    mNative.changeUniqueIdAssociation();
}
```

**权限定义**（`core/res/AndroidManifest.xml`）：

```xml
<!-- Allows the caller to change the associations between input devices and displays.
     Very dangerous! @hide -->
<permission android:name="android.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY"
            android:protectionLevel="signature" />
```

虽然保护级别是 `signature`，但 **shell 已被授予该权限**
（`frameworks/base/packages/Shell/AndroidManifest.xml`）：

```xml
<uses-permission android:name="android.permission.INTERNAL_SYSTEM_WINDOW" />
<uses-permission android:name="android.permission.INJECT_EVENTS" />
<uses-permission android:name="android.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY" />
<uses-permission android:name="android.permission.MANAGE_DISPLAYS" />
```

> **所以：Shizuku（uid=2000）走 binder 调这三个方法，权限是够的。**
> 旁证：SmartDock 的技术文档 `INPUT_ROUTING_A14.md` 也是在 priv-app 权限下
> 通过反射调同一组方法实现 `PHYSICAL_HARDWARE` 模式的。

**已用真实 `aidl.exe` 编译同序骨架验证事务码，全部正确**：

| 方法 | 事务码 |
|---|---|
| `getInputDevice` | 2 |
| `getInputDeviceIds` | 3 |
| `addPortAssociation` | 40 |
| `removePortAssociation` | 41 |
| `addUniqueIdAssociationByDescriptor` | 42 |
| `removeUniqueIdAssociationByDescriptor` | 43 |
| `addUniqueIdAssociationByPort` | 44 |
| `removeUniqueIdAssociationByPort` | 45 |

`IInputManager` 的 descriptor：`android.hardware.input.IInputManager`

**⚠️ 必须知道的限制**：
- 关联是**静态**的。绑定后鼠标**只**往那块屏送事件，不会自己回来。
- 因此**键盘不应该绑**（键事件走焦点窗口），只绑鼠标。
- 这条路**只把指针放到外接屏**，**不提供两屏之间的连续坐标空间**。
  也就是说：绑了鼠标 → 鼠标出现在外接屏 → **但不会"滑过去"，鼠标从平板上消失**。

### 3.2 【做不到】指针在屏幕上连续跨屏（Display Topology）

这是用户真正想要的"滑过去"效果，**AOSP 有专门机制，但它是编译期常量**。

`frameworks/base/services/core/java/com/android/server/display/feature/display_flags.aconfig`
（android-16.0.0_r1）原文：

```
flag {
    name: "display_topology"
    namespace: "display_manager"
    description: "Display topology for moving cursors and windows between extended displays"
    bug: "364906028"
    is_fixed_read_only: true
}
```

`DisplayManagerService.java:683`：

```java
if (mFlags.isDisplayTopologyEnabled()) {
    DisplayTopologyGraph graph = update.second;
    mInputManagerInternal.setDisplayTopology(graph);   // ← 指针跨屏的唯一来源
}
```

`is_fixed_read_only: true` 的含义（AOSP 官方文档原文）：

> **只读 aconfig 标志是布尔常量，无法在运行时更改。**
> 对于稳定且已准备好发布的代码，您可以将读写 aconfig 标志转换为只读 aconfig 标志。

**配套版本配置的权限为 `READ_ONLY`。** `aflags enable` / `device_config put`
**只能改"读写"标志，对只读标志无效**。

**为什么这决定性地堵死了软件方案**：flag 为 false 时
`mDisplayTopologyCoordinator == null`，
`setDisplayTopology` 在服务端**静默空操作**，
`getDisplayTopology` **返回 null** —— 整段代码路径**不存在于编译产物中**。
这不是权限问题，**shell 权限再高也无法调用一段没有被编译进来的代码**。

`DisplayTopology` 相关事务码（本项目 `DisplayTopologyController.kt` 已改为
从 `IDisplayManager$Stub` 动态解析，**读不到即判定不支持，绝不硬编码**）：

| 方法 | 事务码 | 权限 |
|---|---|---|
| `getDisplayTopology` | 63 | `@EnforcePermission("MANAGE_DISPLAYS")` |
| `setDisplayTopology` | 64 | `@EnforcePermission("MANAGE_DISPLAYS")` |

**注意**：shell **持有** `MANAGE_DISPLAYS`（见 §3.1），
所以拓扑调用失败**不是权限问题，是编译期 flag 问题**。

另：`android.hardware.display.DisplayTopology` 类在 AOSP 源码里标注 `@hide`。

### 3.3 【不确定，未验证】`setPointerDisplayId`

```java
// NativeInputManagerService（native 本地接口，不是 Binder 服务）
/** Set the displayId on which the mouse cursor should be shown. */
void setPointerDisplayId(int displayId);
```

**全 AOSP 只有一处调用**：

```java
// InputManagerService.setDisplayViewportsInternal()
mNative.setPointerDisplayId(mWindowManagerCallbacks.getPointerDisplayId());
```

取值逻辑在 `WindowManagerService.InputManagerCallback.getPointerDisplayId()`：

```java
public int getPointerDisplayId() {
    synchronized (mService.mGlobalLock) {
        // 桌面模式没开 → 光标永远留在内屏
        if (!mService.mForceDesktopModeOnExternalDisplays) {
            return DEFAULT_DISPLAY;
        }
        // 找最上层的 freeform 显示器
        for (int i = mService.mRoot.mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent displayContent = mService.mRoot.mChildren.get(i);
            if (displayContent.getDisplayInfo().state == Display.STATE_OFF) continue;
            // When "Freeform windows" developer option is enabled we automatically put
            // secondary displays in freeform mode and emulate "desktop mode".
            if (displayContent.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                return displayContent.getDisplayId();
            }
            ...
        }
        return firstExternalDisplayId;
    }
}
```

**结论**：`setPointerDisplayId` 外部调不到（非 Binder），
但**系统会在满足条件时自己调**——条件是 `mForceDesktopModeOnExternalDisplays = true`。

`mForceDesktopModeOnExternalDisplays` 由 Global settings 驱动
（`WindowManagerService.SettingsObserver`）：

```java
void updateForceDesktopModeOnExternalDisplays() {
    final boolean enableForceDesktopMode = Settings.Global.getInt(resolver,
            DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS, 0) != 0;
    if (mForceDesktopModeOnExternalDisplays == enableForceDesktopMode) return;
    setForceDesktopModeOnExternalDisplays(enableForceDesktopMode);
}

void updateFreeformWindowManagement() {
    final boolean freeformWindowManagement = mContext.getPackageManager().hasSystemFeature(
            FEATURE_FREEFORM_WINDOW_MANAGEMENT) || Settings.Global.getInt(
            resolver, DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;
    if (mAtmService.mSupportsFreeformWindowManagement != freeformWindowManagement) {
        mAtmService.mSupportsFreeformWindowManagement = freeformWindowManagement;
        synchronized (mGlobalLock) {
            mRoot.onSettingsRetrieved();
        }
    }
}
```

**理论链条**：写入这两个键 → 副屏进入 freeform → `getPointerDisplayId()` 返回外屏
→ 系统自己调 `setPointerDisplayId` → **原生鼠标光标出现在外接屏**。

**但实测：这两个键在 ColorOS 上写入失败**（v0.6.0 日志，
`RESULT_OK=false`，5 个键全部失败）。v0.6.1 已改为 shell `settings` 命令
并加入完整异常打印 + `settings list global` 关键词搜索诊断，
**用户尚未回传该版本的输出**。

### 3.4 为什么"强制桌面模式"体验很差（用户实测反馈）

用户原话：「强制桌面模式桌面会变得很失败，只能触屏进行使用」。

原因：`WindowManagerService` 里该字段的注释仍留着未完成项：

```java
/**
 * - Enable system decorations and IME on external screen.
 * - TODO: Show mouse pointer on external screen.
 */
boolean mForceDesktopModeOnExternalDisplays;
```

且 `getPointerDisplayId()` 的 freeform 分支**只把指针放到某一块屏**，
不提供连续坐标空间（那需要 §3.2 的 `display_topology`）。
**所以"半个桌面模式"必然是这个效果，不是用户操作问题。**

---

## 4. 行业权威旁证（强烈建议接手者先读这份）

**[SmartDock DFC — A14 Physical Pointer Routing](https://docs.blisscolabs.dev/applications/smartdockdfc/input_routing_a14/)**
是目前找到的**最直接、最权威**的技术文档。

它的核心信息：

> SmartDock is a **priv-app** with `ASSOCIATE_INPUT_DEVICE_TO_DISPLAY`. On Android 14,
> physical mice and touchpads should be routed via framework APIs — not the software
> overlay relay used as a fallback.

**指针模式表（直接对应用户的目标）**：

| 模式 | 何时 | 有假光标 | 原生指针 |
|---|---|---|---|
| `PHYSICAL_HARDWARE` | 关联成功 | ❌ 无 | ✅ **在目标屏**（用户要的） |
| `PHYSICAL_SOFTWARE` | 关联失败 | ✅ 有 | ❌ 尽力隐藏 |
| `VIRTUAL_TOUCHPAD` | 触控板 | ✅ 有 | ❌ 隐藏 |
| `OFF` | 关闭 | ❌ | 正常 |

**它给出的四条路径及门槛**：

1. **硬件路由**（`addUniqueIdAssociation*`）→ 需 priv-app 权限（**shell 有**）
2. **`cmd input` shell 命令**（`set-pointer-display-id` / `associate`）
   → 文档明说要**给厂商树打框架补丁**才有
3. **静态端口映射** `/vendor/etc/input-port-associations.xml` → 需改系统镜像
4. **软件中转**（自绘光标 + 注入）→ 其原话：
   **"This is not equivalent to true routing — hover events cannot be pilfered on A14"**

**旁证 2**：SmartDock 作者另开了商业版 **SmartDock DFC**，
README 写明 "designed to be built as a **privileged system component**"，
需在 AOSP 构建里加入 `SmartDock.mk`。
**连专门做桌面模式的团队最终也只能靠"编译进系统"解决指针路由。**

---

## 5. 未验证的最大机会（建议接手者从这里开始）

**用户曾尝试"绑定输入到外接屏"并失败，但那次失败是代码 bug，不是权限问题；
修复后从未重测。** 这是目前唯一还没被证伪的软件路径。

### 失败原因（已定位）

v0.3.1 之前，`InputRoutingController` 用**反射读 `getLocation()` 是否非空**
来判断"是否外接输入设备"。该 ROM 上 `getLocation()` 返回空，
于是 6 个输入设备全被过滤 → 报"没有找到可绑定的外接输入设备"。

参考项目 Dextop 的 `PhysicalDeviceRouting.eligibleDevices` 用的是**公共 API**：

```kotlin
device.id >= 0 && device.isExternal && device.descriptor.isNotBlank() && ...
```

### 当前状态（已修，未测）

`InputRoutingController.listInputDevices()` 已改为使用
`InputDevice.getDeviceIds() / getDevice() / isExternal() / sources /
KEYBOARD_TYPE_ALPHABETIC`，并通过反射读 `getAssociatedDisplayId()` 做**读回验证**。
`bindAllExternalInputToDisplay(display, includeMouse = true, includeKeyboard = false)`
已按 Dextop 风格重写（鼠标绑定、键盘不绑）。

### 建议的实测步骤

1. 升级到最新版本，点「⑤ 查看输入设备列表」，**确认是否存在 `external=true` 的鼠标设备**
2. 点「⑤ 绑定输入到外屏」，看三级回退各自结果 + **`读回验证`那一行**
3. 若 `getAssociatedDisplayId()` 读回显示已关联到外屏 → **看物理鼠标是否真的出现在外接屏上**
4. 记录结果：失败时是 `SecurityException`（权限）还是其它（ROM 限制）

**判据**：
- 若成功 → 用户在 `PHYSICAL_HARDWARE` 模式下获得原生鼠标（**不需要假光标、不破坏 Moonlight**），
  这是**唯一可能不刷机就满足需求**的路径
- 若因权限失败 → 说明该 ROM 连 shell 的 `ASSOCIATE_INPUT_DEVICE_TO_DISPLAY` 都被裁掉，
  则**软件路径彻底结束**

---

## 6. 明确已排除的方案（勿重复投入）

| 方案 | 排除理由 |
|---|---|
| `settings put global` 开桌面模式 | 实测 5 个键全部写入失败；且即便写进去，缺 `display_topology` 也补不齐连续坐标空间 |
| 自绘光标 + 注入事件（触控板） | **用户明确否掉**（任务书第 12 条）：破坏 pointer capture / relative mouse mode / hover / 滚轮 / 右键 / 游戏鼠标。参考项目 deskpad-app 文档亦承认"There is no OS mouse pointer on the desktop, so DeskPad draws its own." |
| AccessibilityService 模拟鼠标 | 同上 |
| 第三方桌面启动器（Smart Dock / Taskbar / Nova） | **只改外观，改不了指针归属**。SmartDock 自己的文档把"桌面 UI"与"指针路由"拆成两个组件，后者需 priv-app 权限 |
| 虚拟显示器方案（Dextop / connect-screen / scrcpy `--new-display`） | 是虚拟屏，**不是用户的物理便携屏**；Dextop 对 ColorOS 自评"有限且不完整" |
| 打包第三方 APK（standby / smartdock） | 一是他人的 GPL/商业许可问题，SmartDock DFC 明确 **NOT licensed under the GPL**；二是打包也解决不了指针问题 |
| 硬编码 `IDisplayManager` 事务码 63/64 | 参考项目 Dextop 明确警告此 id 在版本间不稳定，可能误指向受保护的 `requestDisplayModes()`。**已改为从 `Stub` 动态解析，读不到即判不支持** |

---

## 7. 已知可行路线（给用户的结论，交接者可直接复用）

写入 `docs/how-to-get-mouse-spanning.md`。

| 路线 | 说明 | 门槛 |
|---|---|---|
| **A** | 给显示器单独配一个无线鼠标（插显示器 USB 口） | 几十元，**今天可用**，但是"两个鼠标" |
| **B** | 官方「深度测试」解 BL → Magisk root → LSPosed → [Android_16_Desktop_Experience_Enabler](https://github.com/igorb200828/Android_16_Desktop_Experience_Enabler) | **账号需注册满 60 天 + 实名认证**；解锁清空数据；有失败概率 |
| **C** | 换官方支持的设备（官方博客点名 **三星 Galaxy Tab S11**） | 花钱，但 **100% 可用** |

**路线 B 的关键不确定性**：LSPosed 模块能绕过的是
`config_isDesktopModeSupported`（资源布尔值），
**未必能改 `display_topology`（read-only aconfig）**，故有失败概率。

---

## 8. 死过的坑（避免重踩）

### 8.1 事务码两次 off-by-one

AIDL 事务码 = `FIRST_CALL_TRANSACTION + (声明序号 - 1)`（首个方法 = 1）。
本项目在**显示侧**和**音频侧**各错过一次（曾用 1-based 序号）。

**纪律**：任何事务码必须用 `tools/verify-aidl-codes.ps1` 配合真实 `aidl.exe`
编译**同序骨架接口**验证后再用。该脚本要求 **UTF-8 with BOM**（Windows PowerShell 5.1）。

### 8.2 假成功（本项目最严重的反复问题）

用户反复批评"界面报成功、现实没变化"。已修复的实例：

- `applyExtendMode` 无条件输出 `Report(true, …)`，导致 `contains("✅")` 恒真
- `clearAudioOutputPreference` 无条件返回成功
- 媒体音频失败后被静默重定向到通话音频并报成功
- **哨兵值掩盖真相**：读取失败返回 `-999`，日志显示 `-999 -> -999`，
  看起来像"值是 -999"，实际是"根本没读到"
- **异常被吞**：`runCatching` 包住 `ContentResolver` 写入，
  `SecurityException` 被丢弃，导致失败无原因

**纪律**：一切成功判定必须基于**读回验证**（`RESULT_OK=` 机器可读标记 +
`getAssociatedDisplayId()` / `getInt()` 等实际状态回读），异常必须完整打印。

### 8.3 PowerShell 编码事故

用 PowerShell 读写含中文的 `.kt` 文件曾导致 mojibake 与引号丢失。
**纪律**：中文源文件一律用 `write` / `edit` 工具，不要用 shell 重定向。

### 8.4 注释块操作事故

用 shell 字符串替换插入 Kotlin 函数时，多次因行尾差异（CRLF/LF）
导致 `/**` 重复或缺失，产生语法错误。
**纪律**：插入大段代码用 `edit` 工具或按行号插入，并立即编译验证。

### 8.5 空字符串抛异常

AOSP 的 `DisplayInfo` 构造对 `uniqueId` 调用 `Objects.requireNonNull`，
**空串会抛异常**。处理显示器身份时需注意。

---

## 9. 参考项目清单（已克隆或已读）

| 项目 | 路径 / URL | 关键价值 |
|---|---|---|
| **Dextop** | `D:\deepseekharness\_ref\Dextop` · [GitHub](https://github.com/NarYuki/Dextop) | `PhysicalDeviceRouting.kt`（`isExternal` 判定、`getAssociatedDisplayId` 读回）；`DisplayTopologyController.kt`（**警告事务码不稳定、禁止数字回退**） |
| android-display-extend | `D:\deepseekharness\_ref\android-display-extend` | 三级回退输入路由的原始思路 |
| connect-screen | `D:\deepseekharness\_ref\connect-screen` | 同上派生；虚拟显示器方案 |
| AdaptiveScreenPlus | `D:\deepseekharness\_ref\AdaptiveScreenPlus` | `CursorOverlay.java` / `TouchInject.java`：坐标空间必须取自 `dumpsys window displays` 的 `cur=WxH`（`getRealSize/getMetrics` 会被应用兼容缩放污染） |
| scrcpy | `D:\deepseekharness\_ref\scrcpy` | `--new-display` 虚拟屏方案；PR #6009「Associate UHID devices to displays on Android 15」 |
| **SmartDock DFC 文档** | [input_routing_a14](https://docs.blisscolabs.dev/applications/smartdockdfc/input_routing_a14/) | **最权威**：指针模式表、四条路径门槛 |
| deskpad-app | [ARCHITECTURE.md](https://github.com/auxiliaryutils/deskpad-app/blob/main/ARCHITECTURE.md) | AccessibilityService + 自绘光标的完整实现（**不推荐方向**，但技术细节值得参考） |

---

## 10. 代码结构

```
app/src/main/
├── aidl/com/paddisplay/app/IPadDisplayService.aidl   # UserService 接口（40 个方法）
└── java/com/paddisplay/app/
    ├── system/
    │   ├── AidlCodec.kt                      # 手写 IBinder.transact，descriptor 校验硬失败
    │   ├── PhysicalDisplayAccess.kt          # display token / audioServiceBinder / inputManagerBinder
    │   ├── DisplayPowerController.kt         # SurfaceControl → IDisplayManager(58) → PowerManager
    │   ├── DisplayResolutionController.kt    # setUserPreferredDisplayMode=42；尺寸覆盖
    │   ├── DisplayMirrorController.kt        # windowingMode：API34=97/98，API35+=98/99
    │   ├── DisplayTopologyController.kt      # 拓扑；事务码**动态解析**，读不到即不支持
    │   ├── InputRoutingController.kt         # ★ 输入设备关联（三级回退 + 读回验证）
    │   ├── PointerDisplayController.kt       # ★ MouseFlow 探测（AOSP 同构推演）
    │   ├── PointerInjector.kt                # MotionEvent + setDisplayId 反射 + injectInputEvent(11)
    │   ├── CoordinateSpaceProbe.kt           # ★ dumpsys window displays 的 cur=WxH
    │   ├── AudioRoutingController.kt         # AudioProductStrategy 首选设备
    │   ├── PadDisplayUserService.kt          # 全部 AIDL 方法实现
    │   └── SystemDisplayService.kt           # 门面
    ├── display/DisplayRepository.kt          # 显示器枚举 / 镜像证据
    ├── touchpad/                             # ⚠️ 自绘光标方案，用户已否掉
    └── ui/MainViewModel.kt, MainScreen.kt    # Compose 界面
```

★ = 与本需求直接相关。

---

## 11. 给接手者的建议提问顺序

1. **先跑 §5 的实测**（输入设备关联 + 读回验证）——
   这是唯一未被证伪的软件路径，成本最低
2. 若失败，**让用户回传 v0.6.1+ 的「探测桌面模式能力」输出**
   （含 `settings list global` 关键词搜索结果），
   以判定两个开关是"键不存在"还是"写入被拒"
3. 若两条都不通 → **软件路径确认结束**，转 §7 的三条路线，不要再写代码

**如果接手者认为还有别的软件路径，请务必先回答这个问题**：

> 在 `display_topology` 这个 **read-only aconfig flag** 为 false 的 ROM 上，
> 输入系统里如何建立两块屏之间的**连续坐标空间**？
>
> 若无解，则"鼠标滑过去"不可能实现；能实现的只有
> "把鼠标**放到**外接屏"（§3.1，需实测）。

---

## 12. 证据文件位置

| 文件 | 内容 |
|---|---|
| `tools/aosp-snapshots/InputManagerCallback.api36.java` | `getPointerDisplayId()` 完整实现（桌面模式 + freeform 判据） |
| `tools/aosp-snapshots/display_flags.api36.aconfig` | `display_topology` flag 定义（`is_fixed_read_only: true`） |
| `tools/aosp-snapshots/DisplayTopology.main.java` | `DisplayTopology` 类（`@hide`） |
| `docs/pointer-spanning-research.md` | 广泛调研报告（含开源方案全景对照） |
| `docs/how-to-get-mouse-spanning.md` | 给用户的三条路线执行清单 |
| `tools/verify-aidl-codes.ps1` | 事务码验证脚本 |
| `README.md` §11.5 | 「为什么鼠标跨屏做不到」的源码证据记录 |
