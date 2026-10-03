v0.1.8: 决定性实验（系统自带 am start --display）+ 修掉实测到的 Parcel 异常

## 你的报告推翻了我的最后两个假设

从 v0.1.7 的探针报告读到：

| 证据 | 值 | 结论 |
|---|---|---|
| 外屏 `logicalWidth/Height` | **3840×2160** | 满分辨率，无缩放 |
| 外屏 `appWidth/Height` | **3840×2160** | app 渲染目标就是 4K |
| `wm size -d 13` | `Physical size: 3840x2160`，**无 Override** | 没有任何强制尺寸覆盖 |
| 内屏 | 2520×1680（rotation=1） | 内屏是 **3:2** |
| `config_isDesktopModeSupported` | **false** | 本机不支持桌面模式 |
| `development_force_desktop_mode...` | **-999**（不存在） | 这条全局设置在本机没有 |

**所以：不是 Android 的 display mirroring，也不是我的强制尺寸覆盖。**
外屏拿到的是完整 4K 信号。你说的"复制模式"发生在**更上层** ——
ColorOS 的多屏/投屏服务（`dumpsys display` 里那两行 `dynamicallyConfigViewer`
就是它的私有实现）。

我此前"用 force_desktop_mode 打破镜像"的思路在本机**不成立**（该设置不存在）。
这一点我判断错了，不再沿这条线走。

## 黑边的物理解释

内屏 2520×1680 = **3:2**；显示器 3840×2160 = **16:9**。
镜像时内容按 3:2 送出去，在 16:9 面板上左右必然留黑边 ——
这与"系统在放大低分辨率画面"是同一个现象的不同解释，而你的数据支持后者。
**只有让外屏拥有自己的合成表面（真扩展），黑边才会消失。**

## 本版新增：决定性实验

不再靠我猜，改用一个**绕开本应用全部实现**的系统手段：

**④ 外屏开测试窗口** —— 执行系统自带的
`am start --display <displayId> -n com.android.settings/.Settings`：

- 外接屏出现**填满 4K 的独立窗口** → 「扩展」在系统层面成立，
  黑边来自 ColorOS 的投屏/镜像层，需要在它的多屏设置里关掉
- 外接屏**仍然带黑边** → ColorOS 的多屏服务在更上层接管了外接屏

**找 ColorOS 多屏设置** —— 列出设备上所有投屏/多屏/外接显示相关的设置页入口
（外接屏行为由它控制，不是 Android API）。

## 修掉你日志里暴露的一个真实异常

实测日志：

```
❌ AudioManager.removePreferredDevicesForStrategy: 方法不存在
❌ IAudioService.removePreferredDevicesForStrategy:
   android.os.BadParcelableException: Parcel data not fully consumed, unread size: 4
```

`unread size: 4` = 恰好一个 int —— 说明该 ROM 上这个方法的 reply 格式与我按 AOSP
推导的差一个 int。这正是独立审计此前给出的 F4 警告，在真机上被证实了。

既然**反射主通道在你的设备上已经验证可用**
（日志：`✅ 按名称匹配 STRATEGY_MEDIA (id=5)` + 读回验证通过），
本版把读回路径改为：**优先反射，只有反射不可用时才退回 AIDL**，
且所有 AIDL 读取都包在 `runCatching` 里，不再让异常冒泡成 ❌。

## 请按这个顺序做

1. **④ 外屏开测试窗口** → 看外接屏上是否出现满屏的「设置」界面
   （这一步决定后续方向，最关键）
2. **找 ColorOS 多屏设置** → 把列出的入口发我，我帮你定位该进哪个
3. 把这两步的输出发我

如果第 1 步外屏出现了满屏窗口，那问题就 100% 在 ColorOS 的多屏层，
我会转向"如何关闭它的投屏/镜像"而不是继续在 Android API 上打转。
