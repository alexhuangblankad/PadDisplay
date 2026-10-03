v0.1.7: 黑边/镜像专项 —— 探测真实镜像状态并给出修复开关

## 为什么这一版是必要的

用户实测反馈：「无论软件层面怎么报成功，现实层面就是 4K 显示器还有黑边、
无法用 4K 分辨率、只能用镜像复制模式」。

这句话点破了一个我此前判断错的地方：

**`windowingMode == FULLSCREEN(1)` 不能说明"没在镜像"。**
我上一版就是被这个值误导，得出了"已经是扩展模式、所以问题不在这里"的结论。
实际上 AOSP 里是否镜像由 `DisplayContent.shouldBeMirrored()` 单独决定：

```
shouldBeMirrored() = !mDisplayWindowSettings.shouldBeEnabled(...)
                     || (shouldForceDesktopMode()
                         && windowingMode == WINDOWING_MODE_FULLSCREEN)
```

而其中：

```
DisplayManagerService.shouldForceDesktopMode()
  = mDisplayId != DEFAULT_DISPLAY
    && resources.getBoolean(config_isDesktopModeSupported)        // 设备能力
    && Settings.Global.getInt(
         development_force_desktop_mode_on_external_displays) == 1  // 全局开关
```

**黑边正是镜像的典型症状**：镜像时外接屏沿用内屏的分辨率（约 2560×1600），
画面被放大到 4K 面板后周围留黑；同时因为它跟着内屏走，4K 也就设不上。
所以"黑边"和"分辨率设不上"是同一个根因。

## 本版新增

### 1. 镜像状态探针（`probeMirrorState`）

一次输出全部判定依据：

- `Settings.Global.development_force_desktop_mode_on_external_displays` 当前值
- 设备能力 `config_isDesktopModeSupported`
- 外接屏的 `DisplayInfo` 实际字段：
  `logicalWidth/Height`、`appWidth/Height`、`logicalDensityDpi`、`rotation`、`address`
- `dumpsys display` 相关片段

### 2. 内外屏尺寸对照（最直观的镜像证据）

App 侧直接反射 `DisplayManagerGlobal.getDisplayInfo()` 把内屏与外屏的关键字段
**并列打印**，并给出判读：

- 内外屏 `logicalWidth/Height` **完全相同** → 高度疑似镜像
- 两者不同 → 外接屏是独立尺寸（不是镜像）

### 3. 「关闭强制桌面模式」开关（真正的修复手段）

把 `development_force_desktop_mode_on_external_displays` 写成 **0**，
并读回验证。这是 AOSP 让外接屏被强制镜像的条件之一，关掉后外接屏在
FULLSCREEN 下不再被强制镜像，**才有可能独立设 4K、消除黑边**。

同时提供「恢复默认」写回 1。

> 该设置需要 `WRITE_SECURE_SETTINGS`，Shizuku 的 shell 身份持有。

## 请按这个顺序操作

1. 点 **① 探测镜像状态** → 把报告发我（重点看内外屏 `logicalWidth/Height` 是否相同）
2. 点 **② 关闭强制桌面模式** → 看返回的 `RESULT_OK` 与读回值
3. **重新插拔外接屏**（或重启）让显示策略重算
4. 再点 **①** 看镜像是否解除、分辨率列表是否出现更多选项

如果 ② 写入被 ColorOS 拒绝（`RESULT_OK=false`），报告里会体现，
我再换别的突破口（例如直接改 `DisplayWindowSettings` 的 enable 状态）。

## 一点说明

这一版依然**没有真机验证**。但与前几版不同的是：这次不是靠猜，
而是把判定镜像所需的全部依据都摆到报告里 —— 数据出来，根因就明确了。
