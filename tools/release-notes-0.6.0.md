v0.6.0: MouseFlow 第一轮实验 —— 查清了原生鼠标换屏的真实机制

## 先修正我上一版的错误结论

我上一条说"桌面模式不解决鼠标问题"，**那是错的**，我引用的 AOSP 注释被我自己误读了。
查完源码后，真相是：

### `setPointerDisplayId` 确实存在，但外部调不到

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

也就是 **IMS 主动从 WMS 取值**。它不是 Binder 服务，
所以 App / Shizuku / adb 都**无法直接调用**它。

### 但换屏能力系统自己就会做

取值逻辑在 `WindowManagerService.InputManagerCallback.getPointerDisplayId()`：

```java
public int getPointerDisplayId() {
    // 桌面模式没开 → 光标永远留在内屏
    if (!mService.mForceDesktopModeOnExternalDisplays) {
        return DEFAULT_DISPLAY;
    }
    // 找最上层的 freeform 显示器
    for (int i = mService.mRoot.mChildren.size() - 1; i >= 0; --i) {
        ...
        // 「自由窗口」开发者选项打开时，系统自动把副屏设为 freeform 模式，
        // 并模拟"桌面模式"；把指针显示在同一块屏上也顺理成章。
        if (displayContent.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            return displayContent.getDisplayId();     // ← 原生光标去这块屏
        }
    }
    return firstExternalDisplayId;
}
```

**所以"原生鼠标出现在外接屏"的条件就是那两个 Global settings**，
也就是我 v0.5.0 加的那两个开关。**不需要 `setPointerDisplayId`——系统自己会调。**

## 本版新增「MouseFlow 实验」卡片

按任务书要求：**先做实验，不直接写完整功能。**

- **探测原生指针所在屏（先点这个）**
  按 AOSP 同一套逻辑推演系统会把光标放哪、依据是什么；
  枚举物理鼠标（Device ID / Name / Sources / Vendor / Product /
  External / AssocDisplayId / **RelativeAxes**）；
  打印各屏 windowingMode；给出 ADB 辅助诊断命令
- **强制鼠标到内屏 / 强制鼠标到外屏**
  用 `injectInputEvent` + `setDisplayId` 移动指针位置，
  坐标从 `dumpsys window displays` 的 `cur=WxH` 取，取不到就**拒绝盲猜**
- 失败时输出完整异常（SecurityException / RemoteException /
  NoSuchMethodException / Binder 错误都能看到）

## 按任务书调整了方案优先级

我接受你的判断——**"自绘假光标 + 注入"确实会破坏 Moonlight**：

| 会破坏 | 原因 |
|---|---|
| pointer capture / relative mouse mode | 注入的事件不是真实指针事件 |
| hover | 系统不给注入事件点亮指针 |
| 滚轮 / 右键 / 游戏鼠标 | 自绘光标不参与系统指针语义 |

所以那个触控板已**降级标注为"⚠️ 不推荐"**，并写明了它会破坏什么。
推荐路线改为「桌面模式 + MouseFlow」。

## 新增/修改

- `system/PointerDisplayController.kt`（新）
  鼠标枚举、AOSP 同构推演、指针移动、完整异常输出
- `IPadDisplayService.aidl`：`probePointerDisplay` / `forcePointerToDisplay`
- `PadDisplayUserService.kt`：实现 + `dumpsys` 解析 windowingMode
- `SystemDisplayService.kt` / `MainViewModel.kt`：封装
- `MainScreen.kt`：`MouseFlowProbeCard`；触控板卡片降级标注

## 请按这个顺序测（这是本轮唯一关键问题）

1. 连上外接屏 → **① 一键开始**（确保外屏独立）
2. **MouseFlow 实验 → 探测原生指针所在屏** → 把输出发我
3. 点 **桌面模式 → 开启桌面模式**
4. **重新插拔外接屏**（必须，系统才会重算指针归属）
5. 看**物理鼠标**是不是出现在外接屏上了

**第 5 步就是任务书里的 Test A/B。**
若成功，说明 ColorOS 的底层 Pointer Routing 存在，下一步才做边缘自动跨屏
（那时才是纯逻辑问题：`edgeThreshold` + `switchCooldown`）。

## 诚实说明

- 依然没有真机验证。
- 本轮**没有**实现自动边缘跨屏，按任务书第十八条只做第一轮实验。
- 如果第 5 步失败，请把探测输出发我 —— 我会看是哪一层断的：
  键写不进去（ROM 限制）、副屏没进 freeform、还是指针被强制留在内屏。
