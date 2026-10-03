v0.3.1: 读 Dextop 源码后的三处修正 —— 输入绑定判定、拓扑事务号、键盘策略

## 你的探测结果先给结论

```
DisplayTopology 类: 可用
TreeNode 类: 可用
能力探测: supported=false
系统返回 null —— DisplayTopology 的 feature flag 在本机关闭
```

**这台机器上 `DisplayTopology` 被 ROM 关掉了。** 服务端 `mDisplayTopologyCoordinator == null`，
所以 `setDisplayTopology` 是静默空操作。这不是权限问题，是 ColorOS 没启用该能力 ——
**无法靠 App 绕过**（要开它得改系统 config，那就需要 root）。

## 参考项目 Dextop：我读源码后的三个发现

视频里的开源项目是 [NarYuki/Dextop](https://github.com/NarYuki/Dextop)（GPL-3.0）。
它的能力清单里确实写了「跨显示器指针路由」和「鼠标键盘输入路由切换」，
但它对 ColorOS 的自评是：**「有限且不完整：可以显示桌面，但任务栏等系统界面组件可能不会出现」**。

关键是它怎么做的 —— 我读了源码，抓到三处**直接纠正我实现**的地方：

### 1. 输入设备判定我写错了（这是你日志里那个失败的真正原因）

Dextop 的 `PhysicalDeviceRouting.eligibleDevices` 用的是：

```kotlin
device.id >= 0 && device.isExternal && device.descriptor.isNotBlank() && ...
```

**`InputDevice.isExternal()` 是公共 API**。而我用的是反射读 `getLocation()` 字符串是否非空 ——
在你的 ROM 上它返回空，于是**6 个输入设备全被过滤掉**，报"没有找到可绑定的外接输入设备"。

现在改成 `isExternal` + 鼠标/键盘类型筛选，并且把不符合条件的设备**全部列出**，
方便你判断到底是"真没有外接设备"还是筛选条件不对。

顺带用上了 `InputDevice.getAssociatedDisplayId()`（Dextop 用来判断绑定是否生效）
做**读回验证**，不再只看写入有没有报错。

### 2. 拓扑的事务号不该硬编码（我的 63/64 有风险）

Dextop 源码里有一条明确警告：

> Transaction ids ... are not stable across Android releases or OEM framework forks.
> **Never use a numeric fallback here**: on newer builds the old id may point at
> `requestDisplayModes()`, which is protected by `RESTRICT_DISPLAY_MODES`.

我上一版用了硬编码 63/64。现在改成**从 `IDisplayManager$Stub` 读真实
`TRANSACTION_getDisplayTopology` / `TRANSACTION_setDisplayTopology` 常量**；
读不到就**判定为不支持并明确报告**，绝不退回数字。

### 3. 键盘不该绑定（这条影响你的③）

Dextop 把鼠标和键盘**分开控制**（`mouse: Boolean, keyboard: Boolean`）。
原因是我之前分析过的：Android 的设备关联是**静态**的，一旦把键盘绑到外屏，
它就只往外屏送键事件。

所以本版 **默认只绑鼠标、不绑键盘**：鼠标绑到外屏让它能操作，
键盘保持不绑 —— 键事件走**焦点窗口**，这样两块屏按焦点各自接收键盘输入，
才更接近你要的"选哪个屏就在哪里用键盘"。

## 现在的能力边界（如实）

| 你要的 | 状态 |
|---|---|
| 两个桌面 | ✅ 系统层面成立（你已用 `am start --display` 验证满屏 4K） |
| 鼠标能操作外屏 | ⚠️ 修好了判定逻辑，**是否放行要你实测** |
| 键盘跟焦点 | ⚠️ 按上述策略处理，需实测 |
| 屏幕左右关系 | ❌ 本机 `DisplayTopology` 被 ROM 关闭，无法实现 |
| 鼠标自由跨屏拖拽 | ❌ 依赖 DisplayTopology，同上 |

**② 和 ① 在本机做不到，是 ROM 层面关闭能力导致的，不是实现问题。** 我不再在这条路上耗。

## 请这样验证

1. **⑤ 查看输入设备列表** → 把输出发我
   重点看：有没有 `external=true` 的设备、是鼠标还是键盘
2. **⑤ 绑定输入到外屏** → 看三级回退各自的结果 + `读回验证` 那行
   - 若 `external=true` 的鼠标存在且绑定成功 → 试鼠标能否操作外屏
   - 若列表里**没有任何 `external=true`** → 说明你的键鼠没被识别为外接设备
     （可能走的是别的通道），那要换思路
3. 把 **⑦ 探测是否支持** 的完整输出也发我

## 说明

- 依然没有真机验证。
- 如果输入绑定在你机器上仍不生效，我会明确告诉你"这条路在 ColorOS 上不通"，
  并转向**只做两个桌面**（不追求鼠标跨屏）—— 那至少是你说的"两个桌面就够了"。
