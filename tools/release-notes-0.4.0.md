v0.4.0: 应用抽屉（外接屏的「开始菜单」）—— 修掉「只能开设置」

## 为什么你只能打开设置

这是我的实现缺陷，不是系统限制。看你界面上的命令：

```
am start --display 20 -n com.android.settings/.Settings
```

**我把「设置」这个组件写死了**，其他应用根本没有入口。

而且我另外那条代码用的是 `am start -p <包名>` —— 对 `am start` 来说
它期望的是 **component（`-n`）**，`-p` 并不可靠。所以只有显式给了 `-n` 的「设置」能成功。

## 本版修好：应用抽屉

在**主界面**（不用进高级选项）新增 **「打开应用到外接屏」**：

- 解析设备上所有应用的 **launcher Activity 组件**
- 列出应用清单，每个应用后面两个按钮：
  - **外屏** → `am start --display <外接屏> -n <组件>`
  - **内屏** → 启动到内屏
- 这就是外接屏的「开始菜单」，也就是你说的"好好拓展一个桌面"的可操作部分

底层走的是系统自带的 `am start --display`（你已经验证过它能在外屏满屏 4K 显示），
只是这次**用正确的 component 形式**，并且覆盖所有应用。

## 关于「鼠标还是不能跨越屏幕」——我必须说实话

**这条做不到，原因只有一个，而且已经查清。**

AOSP 里指针跨屏的能力来自 `DisplayTopology`：

```java
if (mFlags.isDisplayTopologyEnabled()) {
    mInputManagerInternal.setDisplayTopology(graph);   // ← 光标跨屏就是这里
}
```

而你的机器探测结果是：

```
能力探测: supported=false
系统返回 null —— DisplayTopology 的 feature flag 在本机关闭
```

**ColorOS 把这个 flag 关了。** 服务端 `mDisplayTopologyCoordinator == null`，
所以 `setDisplayTopology` 是静默空操作。

要打开它必须改系统配置（`config_isDesktopTopologyEnabled` 之类的 framework flag），
**那需要 root** —— 超出你一开始定的边界。所以：

| 你要的 | 状态 |
|---|---|
| 两个桌面（外屏独立跑应用） | ✅ 可以（本版的应用抽屉让它真正可用） |
| 鼠标不飘逸 | ✅ 你已验证修好 |
| 分辨率可调 | ✅ 你的 4K@60 本来就是外屏原生模式 |
| **鼠标跨屏拖拽** | ❌ **需要 DisplayTopology，ColorOS 关闭，非 root 无解** |
| 两屏左右/上下位置 | ❌ 同上 |

**我不会再为这一条做无用功，也不会给你"界面成功但现实没变化"的东西。**

## 如果鼠标跨屏对你是刚需，只有两条路

1. **root 后打开该 feature flag** —— 我可以给你具体要改什么，但你说过不做 root
2. **不用物理鼠标跨屏，改用手势控制** —— 参考项目 AdaptiveScreenPlus 就是这条路：
   它不做鼠标跨屏，而是在平板上做一个**触控板页面**，用
   `IInputManager.injectInputEvent` + `MotionEvent.setDisplayId` 把触摸
   **直接注入**到外接屏，并**自绘光标浮层**（因为系统只给真实鼠标画指针）。

**第 2 条我能做**，但它不是"鼠标跨屏"，而是"用平板当外接屏的触控板"。
你要的是哪种，告诉我，我按你的选择做。

## 请这样试

1. 连外接屏 → **① 一键开始**
2. 点 **「打开应用到外接屏」** → **刷新列表** → 点某个应用的 **外屏** 按钮
3. 确认那个应用真的出现在外接屏上

如果某个应用点「外屏」失败，把事件日志里的命令和输出发我 ——
有些 ROM 会限制把第三方应用启动到副屏，那种情况日志会明确报出来。

## 说明

依然没有真机验证。
