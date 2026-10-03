v0.2.0: 找到根因 —— Android 不把输入绑到外接屏；实现输入路由让扩展真正可用

## 你的实验定位到了根因

你执行 ④ 的结果：**外接屏满屏 4K 显示「设置」，完全正常**。

这一条把整个问题一分为二：

| 事实 | 结论 |
|---|---|
| `am start --display 13` → 外屏满屏 4K | **外接屏独立渲染完全成立**，Android 层面"扩展"可用 |
| 鼠标到不了外接屏、设置页卡住无法操作 | **输入没有绑到外接屏** |
| ColorOS 默认给"复制模式" | 镜像时输入自动跟随，**系统是在回避扩展模式的输入问题** |

所以黑边和"只能用复制模式"不是分辨率问题，而是：

**Android 默认不把输入设备（触摸/鼠标/键盘）绑定到外接显示器。**
内屏触摸只对内屏生效、鼠标被限制在默认屏内 —— 于是扩展模式下你"看得见、摸不着"，
自然只能退回镜像。

## 本版实现：输入路由

参照参考项目 `android-display-extend` 的 `InputRouting` 思路，三级回退：

| 级别 | 方法 |
|---|---|
| 1 | `addUniqueIdAssociationByDescriptor(descriptor, displayUniqueId)` |
| 2 | `addUniqueIdAssociationByPort(inputPort, displayUniqueId)` |
| 3 | `addPortAssociation(inputPort, displayPort)` |

**但我按 Android 16 的实际 AIDL 修正了方法名**：参考项目用的
`addUniqueIdAssociation(String, String)` / `removeUniqueIdAssociation(String)`
在 **API 36 的 `IInputManager.aidl` 里已经不存在**（被 `...ByPort` 取代）。
照抄会在你的设备上直接失效。

事务号同样用真实 `aidl.exe` 编译同序骨架验证：

| 方法 | code |
|---|---|
| `getInputDeviceIds` | 3 |
| `getInputDevice` | 2 |
| `addPortAssociation` | 40 |
| `removePortAssociation` | 41 |
| `addUniqueIdAssociationByDescriptor` | 42 |
| `removeUniqueIdAssociationByDescriptor` | 43 |
| `addUniqueIdAssociationByPort` | 44 |
| `removeUniqueIdAssociationByPort` | 45 |

显示器侧靠 `DisplayInfo.uniqueId` 与 `DisplayInfo.address` 里的物理端口
（你的外屏是 `port=21`）来关联。

## 新增界面

- **⑤ 绑定输入到外屏** —— 把外接输入设备关联到外接屏
- **解除绑定** —— 恢复默认（输入跟随默认屏）
- **查看输入设备列表** —— 确认哪些设备是可绑定的外接设备（Location 非空）

## 请这样验证

1. 点 **④ 外屏开测试窗口**（确认外屏能满屏 4K）→ 此时鼠标应该还是过不去
2. 点 **⑤ 绑定输入到外屏**
3. 再试鼠标能不能移到外接屏上、能不能操作外屏的窗口

**如果第 3 步成功**，你就得到了真正的扩展桌面：无黑边 4K + 可操作。
这时再回到「扩展」模式用日常应用即可，不必再用系统的复制模式。

**如果绑定失败**，把「查看输入设备列表」的输出和事件日志发我
（重点看有没有"外接输入设备"、三级回退分别报什么错），我据此调整。

## 诚实说明

- 这一版依然没有真机验证，输入路由是否在你的 ColorOS 上放行需要你实测。
- 输入绑定在部分设备上需要 `INTERNAL_SYSTEM_WINDOW` 或类似权限；
  Shizuku 的 shell 身份通常够用，若被拦截报告里会明确写出。
- 绑定是**系统级**状态，重插显示器或重启即恢复默认；
  本应用也提供「解除绑定」。
