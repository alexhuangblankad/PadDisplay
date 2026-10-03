# 鼠标跨屏（Pointer Spanning）需求 — 广泛调研报告

> 结论先行：**"鼠标光标在两块屏之间自由穿梭"是 Android 16 QPR3 的官方功能，
> 但它需要 ROM 在编译时打开两个开关。你的 OPPO Pad mini / ColorOS 两个都没开，
> 而它们是编译期常量，非 root 无法改变。**
>
> 因此：**要么刷机（你的判断是对的），要么用非原生的替代方案（自绘光标，
> 但有明确的代价）。没有第三条路。**

---

## 一、这个需求在 Android 里叫什么

**Connected Display（已连接的显示屏）** + **Desktop Windowing（桌面窗口化）**
+ **DisplayTopology（显示拓扑）**。

Android Developers 官方博客《Android devices extend seamlessly to connected displays》
（2026-03-03）与 IT 之家（2026-03-05）的报道都明确写了这个体验：

> 针对支持桌面窗口化管理的平板电脑（例如三星 Galaxy Tab S11），连接后
> **桌面会话会直接扩展至两块屏幕上，将它们整合为一个连续运行的系统。**
> 在这个广阔的工作区内，**应用窗口、内容以及鼠标光标均可在平板屏幕与外接显示器
> 之间自由穿梭**。

**"鼠标光标自由穿梭"就是你一直要的东西，它是官方能力。**

---

## 二、决定性证据：它是**编译期常量**，默认关闭

### 证据 1：`config_isDesktopModeSupported` 的 AOSP 默认值是 `false`

`frameworks/base/core/res/res/values/config.xml`（AOSP main）原文：

```xml
<!-- Whether desktop mode is supported on the current device  -->
<bool name="config_isDesktopModeSupported">false</bool>
```

**AOSP 的默认值是 false。** 也就是说：**每个 OEM 必须自己在产品 overlay 里
把它改成 true，设备才具备桌面模式能力。**

你的设备探测结果就是这个值 = **false**（当初我探测过，见 README 11.5 节）。

### 证据 2：`display_topology` 是 **read-only aconfig flag**

`frameworks/base/services/core/java/com/android/server/display/feature/display_flags.aconfig`
（android-16.0.0_r1）原文：

```
flag {
    name: "display_topology"
    namespace: "display_manager"
    description: "Display topology for moving cursors and windows between extended displays"
    bug: "364906028"
    is_fixed_read_only: true       // ← 运行时不可改
}
```

注意 `description`：**"Display topology for moving cursors and windows between
extended displays"** —— 官方对这个 flag 的描述就是"光标跨屏"。

而 AOSP 文档明确：**只读 aconfig 标志是布尔常量，无法在运行时更改。**

### 证据 3：`NativeInputManagerService.setPointerDisplayId` 不是 Binder 服务

```java
/** Set the displayId on which the mouse cursor should be shown. */
void setPointerDisplayId(int displayId);   // native 本地接口
```

全 AOSP 只有一处调用：

```java
// InputManagerService.setDisplayViewportsInternal()
mNative.setPointerDisplayId(mWindowManagerCallbacks.getPointerDisplayId());
```

**IMS 主动从 WMS 取值，App / Shizuku / adb 都无法直接调用。**

### 证据 4：`DisplayTopology` 类本身是 `@hide`

`core/java/android/hardware/display/DisplayTopology.java` 类注释尾部：

```java
 * @hide
 */
public final class DisplayTopology implements Parcelable {
```

虽然 Android Developers 站点把它列进了 API 参考，但 AOSP 源码里它是 `@hide`，
**第三方应用不能直接使用**（需要反射 + 系统权限）。

### 证据 5：社区唯一的"强制开启"方案需要 **root**

[igorb200828/Android_16_Desktop_Experience_Enabler](https://github.com/igorb200828/Android_16_Desktop_Experience_Enabler)
—— 一个 **LSPosed 模块**，作用是开启开发者选项里的
**"Enable desktop experience features"** 开关，从而启用外接屏桌面模式。

**它是 LSPosed 模块 = 需要 root（或 Magisk + LSPosed）。**
而且它自述 "works ONLY on Android 16 QPR1 Beta 1"。

**这条证据最有说服力：连社区都只能靠 root 来打开这个开关，
说明没有非 root 的路径。**

---

## 三、为什么"强制使用桌面模式"在你机器上体验很差

你实测反馈：

> 强制桌面模式桌面会变得很失败，只能触屏进行使用

**这完全符合 AOSP 的实现现状。** 原因有两个：

1. `WindowManagerService` 里那个字段的注释还留着未完成的 TODO：
   ```java
   /**
    * - Enable system decorations and IME on external screen.
    * - TODO: Show mouse pointer on external screen.
    */
   boolean mForceDesktopModeOnExternalDisplays;
   ```

2. `getPointerDisplayId()` 的 freeform 分支只把指针**放到某一块屏**，
   并不提供两块屏之间的**连续坐标空间**。要实现连续穿梭，必须有
   `display_topology`（见证据 2），而它是关闭的。

**所以"半个桌面模式"就是这个效果：界面变了、输入却接不上。**
这不是你操作的问题，是这套机制在缺少 topology 时本来就不完整。

---

## 四、开源方案全景（我实际读过的）

| 项目 | 做法 | 能否满足"原生指针跨屏" |
|---|---|---|
| [NarYuki/Dextop](https://github.com/NarYuki/Dextop) | 解锁 DeX 式桌面；用**虚拟显示器** | ❌ 虚拟屏，不是你的物理外接屏；且对 ColorOS 自评"有限且不完整" |
| [connect-screen](https://gitee.com/connect-screen/connect-screen) | 从 android-display-extend 派生，**虚拟显示器** | ❌ 同上 |
| [AdaptiveScreenPlus](https://github.com/PyMakesMeProud/AdaptiveScreenPlus) | **自绘光标** + 注入事件 | ❌ 非原生指针（你已明确否掉） |
| [auxiliaryutils/deskpad-app](https://github.com/auxiliaryutils/deskpad-app) | **AccessibilityService** + `GestureDescription.setDisplayId()` + `TYPE_ACCESSIBILITY_OVERLAY` 自绘光标 | ❌ 非原生；其 ARCHITECTURE.md 原文承认 "There is no OS mouse pointer on the desktop, so DeskPad draws its own." |
| [ClassicOldSong/AirBeam](https://github.com/ClassicOldSong/AirBeam) | Android AirMouse / 触控板 | ❌ 触控板形态 |
| [farmerbb/Taskbar](https://github.com/farmerbb/Taskbar) | PC 式任务栏 + 开始菜单 | ⚠️ 只解决"桌面外观"，不解决指针 |
| [Smart Dock](https://f-droid.org/packages/cu.axel.smartdock/) | 桌面式 launcher | ⚠️ 同上（你已在用） |
| [igorb200828/…Enabler](https://github.com/igorb200828/Android_16_Desktop_Experience_Enabler) | LSPosed 模块，开启桌面体验开关 | ⚠️ **需要 root** |

**关键负面结论：我找遍了，没有任何一个非 root 的开源项目实现了
"系统原生指针在两块物理屏之间穿梭"。** 所有能跑通的方案都是
"自绘光标 + 注入/无障碍手势"，而这类方案你在任务书第 12 条里已经明确排除，
理由（会破坏 Moonlight 的 pointer capture / relative mouse mode / hover / 滚轮）也是对的。

---

## 五、三条路的代价对照

| 路线 | 能否实现你要的效果 | 代价 |
|---|---|---|
| **A. 刷原生 Android 17 / 支持 Connected Display 的 ROM** | ✅ **能，而且是官方原生体验** | 需要确认该机型有可用的原生/第三方 ROM；会失去 ColorOS 功能与保修；有变砖风险 |
| **B. root + LSPosed 模块** | ⚠️ 可能能（取决于该 ROM 是否也关掉了 topology） | 需要 root；且证据 2 的 `display_topology` 是编译期 flag，**root 也未必能开**（要改 system image） |
| **C. 非原生替代（自绘光标 + 注入）** | ❌ 不是原生指针 | 破坏 Moonlight 等场景；你已否掉 |
| **D. 什么都不做，用 Smart Dock 等桌面壳** | ❌ 只改外观 | 指针问题依旧 |

**注意 A 和 B 的差别：**
- A 是换掉整个 ROM → `config_isDesktopModeSupported` 和 `display_topology`
  可能本来就是 true（取决于目标 ROM）→ **真正可行**
- B 是在现有 ColorOS 上开 → 这两个都是**编译期**的 → 大概率**开不了**

**所以你的判断"实在不行可能真要刷成原生安卓17"在技术上是站得住的，
而且是这几条路里期望收益最高的一条。**

---

## 六、PadDisplay 接下来该做什么

### 不建议再做的

- ❌ 继续折腾 `settings put global`（已实测写入失败；且即便写进去，
  缺 `display_topology` 也补不齐连续坐标空间）
- ❌ 自绘光标 / 触控板（你已否掉，我同意）
- ❌ 继续在 ColorOS 上尝试"半开桌面模式"（实测体验就是"只能触屏"）

### 建议做的（按优先级）

1. **先确认该机型的 ROM 现状**（这是决策前提，属于调研而非开发）
   - 是否有可用的原生 Android 17 / LineageOS / PixelExperience 等
   - 目标 ROM 的 `config_isDesktopModeSupported` 是否为 true
   - Bootloader 能否解锁（OPPO 部分机型限制解锁）
2. **在刷机前，PadDisplay 保留为"显示器控制工具"** ——
   分辨率 / 刷新率 / 内屏开关 / 音频路由这些**是真实有用且已验证的**
3. 若确认刷机可行 → 刷完后 `DisplayTopology` 大概率可用，
   那时 PadDisplay 只需做**布局设置**（左右/上下 + 边缘切换阈值），
   工作量很小

---

## 七、一句话总结

> 你要的功能是 Android 16 QPR3 的官方能力，但它是**编译期开关**
> （`config_isDesktopModeSupported` 默认 false + `display_topology` 只读）。
> ColorOS 两个都没开，**非 root 改不了**。
> 社区唯一的开启方案是 LSPosed 模块（需要 root）。
>
> **所以你的结论是对的：要么刷 ROM，要么接受非原生方案。**
> 我此前所有"在 ColorOS 上想办法"的努力，方向就是错的。
