v0.3.0: 两个桌面 + 屏幕左右关系（DisplayTopology）+ 输入路由

## 你的四个要求，我先说清 Android 的真实能力

| 你的要求 | Android 的实际能力 | 本版做法 |
|---|---|---|
| ① 决定两屏左右关系 | ✅ **有对应 API**：`IDisplayManager.setDisplayTopology` | 已实现，**先探测 + 读回验证** |
| ② 鼠标拖拽跨屏 | ⚠️ **不是自由光标**，但拓扑正是它的机制 | 依赖①；①不可用则退化为设备绑定 |
| ③ 键盘在哪个屏就输到哪 | ✅ 键事件走**焦点窗口**，点哪块屏哪块屏获焦 | 无需额外处理；输入绑定是**静态**的，所以不绑键盘 |
| ④ 两个桌面 | ✅ 已由你的实验证明（`am start --display` 满屏 4K） | 一键双桌面 + 分屏开应用 |

## ① 屏幕左右关系：找到了正确的 API

AOSP `DisplayManagerService` 里那段代码是关键：

```java
if (mFlags.isDisplayTopologyEnabled()) {
    DisplayTopologyGraph graph = update.second;
    mInputManagerInternal.setDisplayTopology(graph);   // ← 喂给输入系统
}
```

**`DisplayTopology` 不只是"画个位置图"** —— 它建立多屏统一坐标空间并驱动
InputManager，这正是 Android 里指针/输入跨屏的机制。所以你的①和②其实是同一件事。

**但我必须先探测**，因为 flag 关闭时服务端是这样的：

```java
public DisplayTopology getDisplayTopology() {
    if (mDisplayTopologyCoordinator == null) return null;   // 返回 null
}
public void setDisplayTopology(DisplayTopology topology) {
    if (mDisplayTopologyCoordinator != null) { ... }        // 空操作，不报错
}
```

`setDisplayTopology` 在 flag 关闭时**静默什么都不做**。所以本版：
**先探测 → 再写入 → 用 `getDisplayTopology()` 读回验证**，
读回不一致就明确告诉你"本机关闭了该能力"，不会给你一个假的 ✅。

## ③ 键盘：为什么不绑它

Android 的输入设备关联是**静态绑定**（设备 → 屏幕），没有"键盘跟随焦点动态切换"。
但键事件本身是走**焦点窗口**的：你点哪块屏，哪块屏获焦，键盘就往哪里输。

所以正确做法是：**把鼠标绑到外屏、键盘不绑**。
这样内屏和外屏各自都能接收键盘输入（取决于焦点在哪）。

## ④ 两个桌面

- **一键建立双桌面**：扩展模式 → 点亮内屏 → 把输入绑到外屏 → 尝试设置左右关系
- **在内屏开应用 / 在外屏开应用**：分别把应用启动到指定桌面
- 底层就是系统自带的 `am start --display <id>`，也就是你已验证可用的那条路

## 新增界面

- ⑥ 一键建立双桌面 / 在内屏开应用 / 在外屏开应用
- ⑦ 探测拓扑支持 / 外屏在左 / 外屏在右
- ⑤ 绑定输入到外屏 / 解除绑定 / 查看输入设备列表

## 请按这个顺序验证

1. **⑦ 探测是否支持** → 看报告里 `supported=true/false`
   - `true` → 左右关系与光标跨屏有戏，继续第 2 步
   - `false` → 本机关闭了该能力，我会转向别的方案（不再在它上面浪费时间）
2. **⑦ 外屏在右** → 看 `RESULT_OK` 与读回内容
3. **⑤ 绑定输入到外屏** → 试鼠标能否操作外屏
4. **⑥ 在外屏开应用** → 外屏出现独立应用窗口

## 诚实说明

- 本版**依然没有真机验证**。`DisplayTopology` 的构造（`TreeNode` + `rearrange`）
  涉及不少隐藏 API 反射，是否在你的 ROM 上可用需要实测。
- 如果②（光标自由跨屏）最终做不到，那是 Android 输入模型的限制，不是实现问题 ——
  我会明确告诉你，而不是继续给你"界面成功、现实没变化"的东西。
