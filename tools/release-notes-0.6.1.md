v0.6.1: 修掉日志无信息量的问题 —— 改用 shell settings，并把失败原因打全

## 你给的日志暴露了我的两个错误

### 错误一：`-999` 是我自己的哨兵值，不是真实值

```
development_force_desktop_mode_on_external_displays: -999 -> -999 (写入返回 false)
```

`-999` 是**我代码里读取失败时返回的占位值**。所以这行真正的含义是
"我根本没读到这个键"，而不是"它的值是 -999"。

### 错误二：我把异常吞掉了（违反你任务书第 15 条）

写入用的是 `ContentResolver`，外面包了 `runCatching`，
**抛出的 `SecurityException` / `IllegalArgumentException` 全被我自己吃掉了**，
所以你看不到失败原因。这正是任务书第 15 条要求"必须打印完整异常"，
**我漏做了**。

## 本版改动

### 1. 改用 shell `settings` 命令（等同 `adb shell settings put`）

不再走 `ContentResolver`。现在每行输出包含：

```
✅/❌ <键名>
    写入前: (无)  ← 键不存在或读取失败（输出：(空)）
    命令: /system/bin/settings put global <键名> 1 | 输出: <settings 的真实输出> | 读回: <实际值>
    写入后: (无)
    失败原因: 写入后读回不匹配
```

**`settings` 自己的报错会原样显示**（`SecurityException` / `unknown setting` / `BadUser` 都能看到）。

### 2. 加了决定性诊断：全量 dump 后搜关键词

新增一段会执行 `settings list global`，然后在全部输出里搜
`desktop` / `freeform` / `multi_window` / `resizable` / `force_desktop`：

- **搜到了** → 键存在，是权限或策略问题
- **一条都没搜到** → 这些键在 ColorOS 的 SettingsProvider 里**根本没注册**，
  也就是**不是权限问题，是 ROM 直接移除了这些开发者选项**

这一条能**一次性判定**是哪种情况，不用再猜。

### 3. 顺带报告 `settings` 命令本身是否可用

（`which settings`、`settings --help` 前几行），排除"命令环境有问题"这种可能。

## 请这样测

1. **桌面模式 → 探测桌面模式能力** → 把输出发我
   （重点看最后那段"关键诊断"和"settings 命令本身是否可用"）
2. **桌面模式 → 开启桌面模式** → 把每行的完整输出发我
   （重点看每行里的「命令」和「输出」）

## 我不预设结论

如果 dump 里搜不到那些键，那说明 **ColorOS 从 ROM 层面移除了这些开发者选项**，
那么"桌面模式"这条路在你这台机器上也是死的 —— 我会明确告诉你，
而不再继续在它身上投入。

如果搜到了但写入被拒，那可能是权限或策略，还有别的走法（比如换用
`setprop` 或直接调用 WMS 的 binder 方法，前提是存在可调用的接口）。

**先把事实拿到，再决定下一步。** 这次我不猜了。

## 说明

依然没有真机验证。本版是纯诊断改进：把"看不到原因"变成"原因直接打出来"。
