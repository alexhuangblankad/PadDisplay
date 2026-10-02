# PadDisplay

**Android 外接显示器控制面板** —— 面向 OPPO Pad mini / ColorOS 的极简工具。

通过 USB-C（DisplayPort Alt Mode）连接物理显示器后，PadDisplay 可以：

- 枚举所有显示器并读取**硬件真实上报**的分辨率与刷新率
- 一键把外接屏切到**最佳分辨率**（最高分辨率 + 默认 60 Hz）
- **只关闭平板内屏**，外接屏继续显示，Wi-Fi / 蓝牙 / 键鼠 / Moonlight 全部不受影响
- **外接屏拔出时自动恢复内屏**（硬性 failsafe，防止永久黑屏）
- 切换分辨率时提供 **15 秒防黑屏确认倒计时**，不确认自动回滚

> **不需要 Root。** 权限来源只有 Shizuku。

当前版本：**v0.1.0**

---

## 1. 它不是什么

本项目刻意**只做**「物理外接显示器控制面板」。以下功能**一律不做**：

桌面 Launcher / DeX 类桌面环境 / 窗口管理 / 多任务栏 / 触控板 / 鼠标键盘映射 /
无线投屏 / Miracast / DLNA / 虚拟显示器 / 屏幕镜像 / 音频输出管理 / HDR 管理 /
色彩管理 / 远程桌面 / 文件传输 / Termux。

---

## 2. 技术边界（第一版定死的范围）

| 项 | 结论 |
|---|---|
| Root | **不使用** |
| 桌面模式 | 不做 |
| 投屏 / 镜像 / 虚拟屏 | 不做 |
| 显示器枚举 | Android 公共 API（`DisplayManager` / `Display.getSupportedModes`） |
| 关屏 / 开屏 | Shizuku UserService + `SurfaceControl.setDisplayPowerMode()` / `IDisplayManager.requestDisplayPower()` |
| 外屏分辨率 | `IDisplayManager.setUserPreferredDisplayMode()`，回退 `IWindowManager.setForcedDisplaySize(displayId,w,h)` |
| 修改整机默认分辨率 | **禁止**（绝不调用不带 `-d` 的 `wm size`） |

---

## 3. 权限架构

```
PadDisplay (app uid)
     │  Shizuku.requestPermission()
     ▼
Shizuku 服务（运行在 shell 身份下）
     │  Shizuku.bindUserService()
     ▼
PadDisplayUserService   ← 权限中枢，独立进程 uid = 2000 (shell)
     │  ① ServiceManager.getService("display"/"window")
     │  ② android.view.SurfaceControl 反射
     │  ③ sh -c "dumpsys / wm"
     ▼
Android 系统服务（IDisplayManager / IWindowManager / SurfaceFlinger）
```

**为什么 shell 身份就够**（这是本项目可行的关键，均已核实）：

- `IDisplayManager.setUserPreferredDisplayMode` 需要 `MODIFY_USER_PREFERRED_DISPLAY_MODE`
  → `protectionLevel="signature"`，platform 签名的 `com.android.shell` 持有。
- `IDisplayManager.requestDisplayPower` 需要 `MANAGE_DISPLAYS`
  → 同样是 `signature` 级，shell 持有。
- `SurfaceControl.setDisplayPowerMode()` **没有 Java 层权限检查**，
  真正的门在 SurfaceFlinger，shell 属于可信调用方。

也就是说：**Shizuku 提供的 shell(uid=2000) 身份，正好覆盖了本项目的全部系统调用需求。**

---

## 4. 关键实现说明（为什么不是「看起来实现了，其实只改了主屏」）

### 4.1 显示器角色判定，不假设 `displayId == 0`

`DisplayDetector` 采用多证据判定，并把每一项证据都输出到诊断信息：

1. 用户手动指定（最高优先级，会被保存）
2. `displayId == DEFAULT_DISPLAY`
3. 隐藏方法 `Display.getType() == TYPE_INTERNAL(1)`
4. 物理地址 `local:0`（`Display.getAddress()`，端口 0 = 内屏）
5. `FLAG_PRESENTATION + FLAG_PRIVATE`
6. 兜底：已知存在内屏时，其余一律按外接处理

> 注意：`Display.TYPE_*` 常量与 `getType()` / `getAddress()` **都是隐藏 API**，
> 公共 `android.jar` 里只有 `DEFAULT_DISPLAY` / `INVALID_DISPLAY`，
> 所以 `DisplayDetector.DisplayType` 是按 AOSP 取值自行定义的。

### 4.2 分辨率：Mode 切换 vs 逻辑尺寸覆盖，语义完全不同

| 通道 | API | 效果 |
|---|---|---|
| A（首选） | `IDisplayManager.setUserPreferredDisplayMode(displayId, Display.Mode)` | 切换**真实硬件时序**，mode 必须来自 `display.supportedModes` |
| B（回退） | `IWindowManager.setForcedDisplaySize(displayId, w, h)` | 逻辑尺寸覆盖 + 系统缩放，不改时序 |

两条通道都**显式要求 displayId**，因此只作用于目标屏。

关于 `wm size` 的重要区别：

```
wm size 1920x1080              ← 改整个系统默认分辨率，本项目禁止使用
wm size -d <displayId> 1920x1080   ← per-display，等价于 setForcedDisplaySize(displayId,...)
```

AOSP `WindowManagerShellCommand.runDisplaySize()` 里 `getDisplayId("-d")` 会把
`-d` 后面的 displayId 传给 `setForcedDisplaySize`。**本项目一律走带 displayId 的通道。**

### 4.3 transaction code 不是猜的，是从 AOSP AIDL 解析出来的

AIDL 约定：**方法在 `.aidl` 里的 0 起始声明序号 + 1 = transaction code**，
也就是**第一个方法 = code 1**。（本项目第一版曾把序号当成 1 起始，
导致所有 code 整体 +1 —— transact 会「成功」，但调用的是完全不同的方法，
静默什么都不做。这类错误不会抛异常，所以已专门修正并在代码里注明。）

项目在 `tools/aidl/` 下保留了从 AOSP 各 tag 下载并解析的
`IDisplayManager.aidl` / `IWindowManager.aidl`，并用真实编译器验证过公式
（对 4 个方法的测试接口，`aidl.exe` 生成 `TRANSACTION_methodA = FIRST_CALL_TRANSACTION + 0`）。
最终确定值：

| 方法 | API 34 | API 35 | API 36 |
|---|---|---|---|
| `IDisplayManager.getDisplayInfo` | 1 | 1 | 1 |
| `IDisplayManager.getDisplayIds` | 2 | 2 | 2 |
| `IDisplayManager.setUserPreferredDisplayMode` | 42 | 42 | 42 |
| `IDisplayManager.getUserPreferredDisplayMode` | 43 | 43 | 43 |
| `IDisplayManager.requestDisplayPower` | — | 58 | 58 |
| `IWindowManager.getInitialDisplaySize` | 6 | 5 | 5 |
| `IWindowManager.getBaseDisplaySize` | 7 | 6 | 6 |
| `IWindowManager.setForcedDisplaySize` | 8 | 7 | 7 |
| `IWindowManager.clearForcedDisplaySize` | 9 | 8 | 8 |

几个必须注意的坑：

- `IDisplayManager` **没有** `resetUserPreferredDisplayMode` 方法。
  重置的做法是给 `setUserPreferredDisplayMode` 传 **null Mode**（code 仍为 42），
  AOSP `DisplayManagerService.setUserPreferredDisplayModeInternal()` 明确支持 `mode == null`。
- `requestDisplayPower` 的签名在 API 36 从 `(int, boolean)` 变成 `(int, int)`，
  但**两者 code 都是 58**（API 36 只是在其后追加了 `requestDisplayModes` → 59）。
- `in Mode mode` 这类 Parcelable 参数被编译成 `writeTypedObject`：
  先写 1 表示非空，再写内容。**不能用 `writeParcelable`**
  （那会先写 creator 类名字符串，服务端按 typed object 解析会把类名当数据）。
- `out Point size` 这样的输出参数，reply 里同样先有一个 1/0 存在标记，
  必须先 `readInt()` 再 `readFromParcel()`。

**本项目不再做「code 兜底扫描」。** 早先版本在精确 code 失败时会遍历 1~80，
这是危险的：AIDL 不校验方法身份，扫描一旦命中
`disableConnectedDisplay`(57) / `setBrightness`(36) 之类的写方法，
就会带着错位参数真的执行副作用，而且因为没抛异常还会被判定为「成功」。
现在**只发精确 code**，并且调用前用 `IBinder.getInterfaceDescriptor()`
校验 binder 身份，失败就如实上报，由上层切换别的通道。

诊断信息里会输出完整的「AIDL 调用日志」，ColorOS 上排查主要靠它。

### 4.3.1 API 36 的两种状态枚举不一样

`requestDisplayPower` 的 int 版接收的是 `Display.STATE_*`，**不是** `SurfaceControl.POWER_MODE_*`：

```
SurfaceControl.POWER_MODE_* : OFF=0 DOZE=1 NORMAL=2 DOZE_SUSPEND=3 ON_SUSPEND=4
Display.STATE_*             : UNKNOWN=0 OFF=1 ON=2 DOZE=3 DOZE_SUSPEND=4 ON_SUSPEND=6
```

`DisplayManagerService.requestDisplayPower()` 把 `STATE_UNKNOWN(0)` 解释为
「保持当前状态」，所以直接传 0 **不会关屏**，还会返回 true。
代码里做了显式映射。

### 4.3.2 音频：`IAudioService`

同样用真实编译器验证过（API 36 / API 35）：

| 方法 | API 35 | API 36 |
|---|---|---|
| `getAudioProductStrategies` | 35 | 37 |
| `setPreferredDevicesForStrategy` | 144 | 153 |
| `removePreferredDevicesForStrategy` | 145 | 154 |
| `getPreferredDevicesForStrategy` | 146 | 155 |
| `setCommunicationDevice` | 177 | 186 |
| `getCommunicationDevice` | 178 | 187 |

### 4.3.3 一条命令复核所有 code

```bash
powershell -ExecutionPolicy Bypass -File tools/verify-aidl-codes.ps1
```

脚本会把 `tools/aidl/` 下 AOSP 原版 AIDL 的方法名按声明顺序抽出来，
生成同序骨架交给**真实 `aidl.exe`** 编译，再读编译器写出的
`TRANSACTION_* = FIRST_CALL_TRANSACTION + n`。任何 code 改动都应该先跑它。

> 这个脚本的存在是有代价换来的：本项目在 transaction code 上**踩过两次坑**
> （display 一次、audio 一次），两次都是整体差 1，
> 而症状都是「transact 成功但什么都没做」。
> 所以现在所有 code 都必须经过编译器验证，不再靠文本解析器或手算。

### 4.3.4 「成功」的判定方式（踩过的坑）

早期版本用**输出文本里有没有 `✅` 或「结果: 成功」**来判断操作是否成功。
这出过假成功：某些信息性输出本身带 `✅`（例如"当前 windowingMode = ✅…"），
于是即使真正要做的 `set` 完全没生效，上层也判定为成功 ——
典型的"静默失败却报成功"。

现在统一改成：UserService 输出一行**机器可判定**的结果标记

```
RESULT_OK=true     /     RESULT_OK=false
```

上层只认这一行；所有 `✅` 前缀仅用于人类阅读，不参与任何判定。

### 4.3.5 音频主通道：反射 `AudioManager`，而不是手搓 AIDL

音频的媒体路由**首选**反射调用 `AudioManager` 的隐藏方法
（`setPreferredDevicesForStrategy` / `getPreferredDevicesForStrategy` /
`removePreferredDevicesForStrategy`），策略对象来自
`AudioProductStrategy.getAudioProductStrategies()` 并按 `getName() == "STRATEGY_MEDIA"`
匹配（`AudioProductStrategy` 里**没有** `AUDIO_STRATEGY_MEDIA` 这个 Java 常量，
媒体策略 id 是 ROM audio policy 配置里的序号）。

**为什么这是主通道**：手搓 transaction 需要同时赌对
①方法序号 ②Parcel 编组 ③列表读取格式 —— 三处任一错都会静默失效。
直接调用 framework 自己的方法，把这三件事交回 AOSP 保证。
手搓 AIDL 仅作为兜底，且无论走哪条通道，最终都以**读回验证**为准。

> ⚠️ 已知的不确定点：`IAudioService` 兜底通道的 API 35 值，
> 本项目用真实编译器得到 `setPreferredDevicesForStrategy = 144`，
> 而独立审计（文本解析）得到 `145`。目标是 API 36（ColorOS 16），
> 该版本两者一致（153）。API 35 分支属兜底路径，且有读回验证兜住，
> 不会造成静默错误。**目标设备上以反射主通道为准。**

### 4.3.6 音频安全：不跨会话残留

音频的「首选设备」偏好写在系统 `AudioService` 策略状态里，**比 App 活得更久**
（卸载 App 不会清除，重启才恢复）。为把副作用降到最低：

1. **每一次** Shizuku 连接建立时都清除一次（不只是 App 启动时清）
2. App 启动自愈带**重试**（循环等 Shizuku 就绪，最多 12 次）——
   授权往往发生在启动之后，只清一次等于没清
3. **拔掉外接屏时自动清除**
4. 媒体目标**只动媒体**，不再偷偷退化去改通话音频（那会造成"报成功但媒体没动"）
5. 清除操作的成功与否**由读回验证决定**，不再无条件返回成功
6. 只写首选设备偏好，**不碰音量 / force-use / 设备连接状态**

> 万一系统音频真的卡住，**重启设备一定能恢复**。

### 4.4 关屏通道优先级（ColorOS 兼容层）

```
1. SurfaceControl.setDisplayPowerMode(token, 0/2)        ← scrcpy / DisplayToggleExtreme 用的通道
2. IDisplayManager.requestDisplayPower(displayId, ...)   ← Android 15+，精确 per-display
3. PowerManager.wakeUp()（反射）                          ← 仅用于「点亮内屏」的最后兜底
```

依次尝试，任一条成功即停止，并把每条通道的结果显示在 UI 上。

**不使用 `PowerManager.goToSleep()`**：它是整机级操作，会把外接屏一起关掉，
直接违背「只关内屏、外屏继续工作」的目标。它只在恢复方向（`wakeUp`）作为兜底保留。

### 4.4.1 物理屏 token 在 Android 14 起换了地方

`SurfaceControl.getPhysicalDisplayIds()` / `getPhysicalDisplayToken()` /
`getInternalDisplayToken()` 在 Android 14 被移到了
`com.android.server.display.DisplayControl`（在 `services.jar` 里，依赖本地库 `android_servers`）。

`PhysicalDisplayAccess` 会按顺序尝试两条路，Android 14+ 时按
DisplayToggleExtreme 的做法用 `ClassLoaderFactory.createClassLoader("/system/framework/services.jar", …)`
加载 `DisplayControl` 并显式 `loadLibrary0(..., "android_servers")`。
诊断信息里会显示**最终生效的是哪条渠道**，这对定位问题很关键。

### 4.5 failsafe：外接屏拔出 → 立即点亮内屏

`DisplayHotplugManager` 监听 `DisplayManager.DisplayListener`。
只要检测到「已无外接屏」，就**无条件**恢复内屏，且：

- 不依赖自动模式开关是否打开
- 不依赖内屏是不是「被我们关的」（`internalDisplayTurnedOff` 只用于放宽条件，不作为前置条件）
- App 启动时也会做一次自检：若没有外接屏，尝试恢复内屏

---

## 5. 编译

### 5.1 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | 需要 `platforms/android-36`、`build-tools/36.0.0`（35.0.0 亦可） |
| Gradle | 8.13（由 `gradlew` 自动下载） |
| Kotlin | 2.2.21 |
| Android Gradle Plugin | 8.13.1 |

### 5.2 配置 SDK 路径

编辑 `local.properties`：

```properties
sdk.dir=D:/android-sdk
```

（或设置环境变量 `ANDROID_HOME`）

### 5.3 构建 release APK（v0.1.0）

```bash
# Windows
gradlew.bat :app:assembleRelease

# macOS / Linux
./gradlew :app:assembleRelease
```

产物：

```
app/build/outputs/apk/release/app-release.apk
```

### 5.4 签名

仓库内附带了一个**仅供构建调试使用**的密钥库：

```
keystore/paddisplay-release.jks
别名 paddisplay    口令 paddisplay2024
```

可在 `gradle.properties` 里覆盖，避免把口令写进仓库：

```properties
paddisplay.keystore=/absolute/path/your.jks
paddisplay.keystore.password=xxx
paddisplay.key.alias=xxx
paddisplay.key.password=xxx
```

> ⚠️ 正式对外分发前，**请务必换成你自己的密钥库**。
> 该密钥库的口令是公开的，任何拿到它的人都能签出与你相同签名的包。

---

## 6. 安装与使用

### 6.1 准备 Shizuku

1. 安装 [Shizuku](https://shizuku.rikka.app/)（应用商店或 GitHub）
2. 启动 Shizuku 服务（Android 11+ 可用无线调试，无需电脑；全程不需要 Root）
3. 安装 PadDisplay：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 6.2 授权

打开 PadDisplay → 在「系统权限」卡片点 **授权 Shizuku** → 弹窗中允许。
连接成功后首页显示：

```
● Shizuku 已连接，系统控制权限已获得（shell uid=2000）
```

> 即使**不授权**，第一屏的显示器枚举与 `supportedModes` 读取依然可用
> —— 因为那部分只用公共 API。

### 6.3 关闭平板内屏

1. 用 Type-C 连接便携显示器
2. 确认「外接显示器」卡片出现，并列出支持的模式
3. 点 **关闭平板屏幕**

此时：内屏 panel 真正断电熄灭，外接屏继续输出，系统继续运行。

点 **重新打开平板屏幕** 恢复。

### 6.4 切换外接屏分辨率

1. 在「分辨率」列表选择目标分辨率（来自 `supportedModes`，硬件真实支持）
2. 如该分辨率有多个刷新率，会额外出现「刷新率」列表（60 Hz 标注为推荐）
3. 点 **应用**，或直接点 **使用最佳分辨率**
4. 弹出 15 秒倒计时确认框：
   - 画面正常 → 点 **保留**
   - 外屏黑屏/花屏 → 等它自动回滚，或点 **立即恢复**

### 6.5 外接屏显示模式（复制 / 扩展 / 仅外接屏）

对标 Windows 的 Win+P：

| 模式 | 实现方式 | 说明 |
|---|---|---|
| **扩展** | `IWindowManager.setWindowingMode(外屏, FULLSCREEN)` | 外接屏成为独立屏幕，可单独设分辨率 |
| **复制** | 系统行为 | Android 14+ 已移除强制镜像 API，本应用**不**强制切换 |
| **仅外接屏** | 关闭内屏 panel | 只用外接屏，Wi-Fi / 蓝牙 / 键鼠继续工作 |

**为什么"扩展"很重要**：Android 外接屏**默认可能是镜像**。镜像状态下内屏与外屏
共用同一个 layer stack，此时外屏分辨率**改不动**，关内屏还可能把外屏一起黑掉。

代码里从 AOSP 各 tag 的 `IWindowManager.aidl` 确认：
**`setDisplayIdToMirror` 在 API 34/35/36 里都不存在**（Android 14 起移除）。
所以本应用改用 per-display 的 `setWindowingMode`，并且**以系统回读为准**：

```
setWindowingMode(displayId, FULLSCREEN)
  ↓
getWindowingMode(displayId) 读回验证
  ↓
一致才报告成功；不一致就如实报告失败（不再出现「报成功其实没做」）
```

### 6.6 音频输出选择（解决「一线连耳机没声」）

**问题成因**：Android 默认把 USB-C / DisplayPort 显示器当成音频输出设备。
一线连之后音频策略把媒体音频切到显示器，用户连着的蓝牙耳机就"没声"了。
另外，`setForcedDisplaySize` 之类的显示配置变更会让系统重跑显示+音频策略重算，
**于是音频被重新路由到显示器** —— 这就是"用了显示器控制软件之后耳机才没声"的成因。

**解法**：界面上直接选择声音从哪个设备出。

| 通道 | API | 权限 | 能力 |
|---|---|---|---|
| 1（首选） | 反射 `AudioManager.setPreferredDevicesForStrategy()` | `MODIFY_AUDIO_ROUTING`（shell 持有） | **固定媒体音频输出 ← 真正的解** |
| 2（兜底） | `IAudioService.setPreferredDevicesForStrategy()` | 同上 | 主通道不可用时才走 |
| 3（公共） | `AudioManager.setCommunicationDevice()` | 无 | 只能影响**通话音** |

界面提供：

- 可用输出设备列表（标出**当前媒体输出**与「显示器类」设备）
- **设为音频输出** / **恢复自动**
- 开关 **「接入外屏时自动把声音留在平板侧」**（默认开启）
  优先蓝牙/有线耳机，其次平板内置扬声器

#### 关于"卸载后声音还是坏的"——本项目的处理策略

音频的「首选设备」偏好写在系统 `AudioService` 的策略状态里，**比 App 活得更久**，
卸载 App 不会自动清除。为把副作用降到最低，本实现刻意遵守：

1. **不做任何跨会话残留**：App 每次启动都会先清除本应用可能设置过的音频偏好，
   交回系统自动路由（事件日志里会看到「启动自愈」）。
   用户想在本次会话固定输出，就在界面上显式点一次。
2. **拔掉外接屏时自动清除**音频固定（此时"显示器抢音频"的问题本身就不存在了）。
3. 只写 AOSP 的**首选设备**偏好，**不碰音量、不碰 force-use、不改设备连接状态**。
4. 每次写入后**立刻读回**（`getPreferredDevicesForStrategy`）确认，
   读回不一致就判定失败并如实报告，绝不谎报成功。
5. 偏好设置存在 App 私有 DataStore 里，卸载即消失。

> 需要说明的是：本项目源码中**没有任何音频 API 调用**，也不申请任何权限
> （`AndroidManifest.xml` 里连一条 `uses-permission` 都没有），
> 因此它不会去修改系统音频路由。上面的功能是**为了解决问题而新增**的能力，
> 而不是原本就在动音频。

### 6.7 自动模式

| 开关 | 作用 |
|---|---|
| 接入外屏后使用最佳分辨率 | 最高分辨率 + 默认 60 Hz |
| 外屏连接后关闭平板内屏 | 真正关 panel，不是黑色遮罩 |
| 外屏拔出后自动恢复内屏 | **failsafe，建议始终开启** |
| 真实 Mode 失败时回退到逻辑尺寸覆盖 | 通道 A 失败时启用通道 B |
| 外屏连接成功后启动 Moonlight | 用 `getLaunchIntentForPackage` 启动，未安装则忽略 |

「最佳分辨率」的选取规则：

1. 优先选择**物理像素面积最大**的有效模式
2. 同分辨率下**默认优先 60 Hz**（USB-C DP Alt Mode 带宽/线材/转接器兼容性最好）
3. 没有 60 Hz 时，取最接近 60 Hz 的模式（略高优先）
4. 用户仍可手动选择 120 Hz 等更高刷新率

### 6.8 诊断信息

右上角 **诊断信息** → 输出设备信息、Shizuku 状态、每块 Display 的
id/type/flags/state/address/物理屏 token/当前 Mode/首选 Mode/全部 supportedModes，
以及 UserService 自检、物理屏 token 的**生效渠道**、与 AIDL 调用日志。

点 **复制诊断信息** 可一键复制，发给开发者排查 ColorOS 兼容问题。

**拿到机器后请先看这三行**，它们决定了两个核心功能能否工作：

```
SurfaceControl.setDisplayPowerMode: true/false
DisplayControl(services.jar): 可用/不可用
生效渠道: SurfaceControl.getPhysicalDisplayToken / DisplayControl.getPhysicalDisplayToken
```

若两项都是不可用，则关屏只能依赖 `IDisplayManager.requestDisplayPower`（Android 15+）。

---

## 7. 验收清单

| # | 验收项 | 对应功能 |
|---|---|---|
| 1 | 连接 USB-C 显示器后，能同时显示内屏与外屏两个不同 Display | `DisplayRepository.enumerate()` |
| 2 | 能读取外屏真正支持的 width / height / refreshRate / modeId | `Display.getSupportedModes()` |
| 3 | 点「使用最佳分辨率」后外屏切到原生/最高有效分辨率 | `setUserPreferredDisplayMode` |
| 4 | 点「关闭平板屏幕」后内屏真正熄灭，外接屏保持正常 | `SurfaceControl.setDisplayPowerMode` |
| 5 | 内屏关闭后 Wi-Fi / Moonlight / 蓝牙 / 键鼠均正常 | 关屏不睡眠系统 |
| 6 | 拔掉 USB-C 后内屏自动重新点亮 | `DisplayHotplugManager` failsafe |

---

## 8. ColorOS 兼容性排查

遇到 `SecurityException` / `NoSuchMethodException` / Binder 拒绝 / 方法不可用，
**不要改用 Root**。按以下顺序处理：

1. 打开 **诊断信息**，复制全文
2. 运行 `adb shell dumpsys display` 对比系统视角的 Display 列表
3. 确认 Android API level 与 `IDisplayManager` / `IWindowManager` 的 code 表匹配
4. 查看诊断信息里的「AIDL 调用日志」，确认是哪一条 code 失败、失败原因是什么
5. 对比参考项目的实现（见下）

代码层面统一用兼容适配器（`DisplayPowerController` / `DisplayResolutionController`
的多通道链式尝试），**不会因为某条通道失败就直接放弃**。

---

## 9. 项目结构

```
app/src/main/
├── aidl/com/paddisplay/app/
│   └── IPadDisplayService.aidl          Shizuku UserService 接口
├── java/com/paddisplay/app/
│   ├── ui/
│   │   ├── MainActivity.kt              Compose 入口 + 深色主题
│   │   ├── MainScreen.kt                主界面（单页）
│   │   ├── MainViewModel.kt             状态管理 / 防黑屏倒计时
│   │   ├── DisplayCard.kt               显示器卡片 / 支持模式列表
│   │   ├── PermissionScreen.kt          Shizuku 权限引导 + 诊断页
│   │   └── ConfirmDialog.kt             15 秒确认倒计时对话框
│   ├── display/
│   │   ├── DisplayInfo.kt               数据模型
│   │   ├── DisplayRepository.kt         Phase 0：枚举 + supportedModes
│   │   ├── DisplayDetector.kt           内屏/外屏判定
│   │   └── DisplayModeSelector.kt       最佳分辨率选择
│   ├── system/
│   │   ├── PadDisplayUserService.kt     ★ shell 权限进程
│   │   ├── SystemDisplayService.kt      业务门面
│   │   ├── DisplayPowerController.kt    关屏/开屏兼容层
│   │   ├── DisplayResolutionController.kt 分辨率/Mode 控制
│   │   ├── AidlCodec.kt                 隐藏 AIDL 调用（精确 code）
│   │   └── Reflect.kt                   隐藏 API 反射工具
│   ├── shizuku/
│   │   └── ShizukuManager.kt            权限 + UserService 绑定
│   ├── automation/
│   │   └── DisplayHotplugManager.kt     热插拔 + failsafe
│   └── data/
│       └── SettingsRepository.kt        DataStore 配置持久化
tools/aidl/                              AOSP AIDL 原始文件（code 表依据）
keystore/                                构建用密钥库
```

---

## 10. 参考项目与致谢

本项目的隐藏 API 用法参考了以下开源项目（**独立实现，未复制其源码**）：

| 项目 | 参考点 |
|---|---|
| [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | `SurfaceControl.setDisplayPowerMode` 物理屏 token、版本兼容处理 |
| [xe5700/DisplayToggleExtreme](https://github.com/xe5700/DisplayToggleExtreme) | Shizuku UserService 关屏、display 白名单（只关指定屏）思路 |
| [jqssun/android-display-extend](https://github.com/jqssun/android-display-extend) | `IWindowManager.setForcedDisplaySize` per-display 配置、`IDisplayManager` 调用 |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | 无 Root 的 shell 权限通道 |

---

## 11. 许可

**GPL-3.0**（与相关参考项目的许可保持一致，避免许可证冲突）。

---

## 12. 已知限制（v0.1.0）

- **未在真机上验证过**（本版本在无设备环境下构建）。首次使用请务必先看诊断信息。
- **failsafe 依赖 App 进程存活**：若在「内屏已关」的状态下 App 进程被 ColorOS 冻结或杀掉，
  拔线时不会有监听器响应。为此做了两层补偿：
  ① 内屏关闭状态会**持久化**，App 下次启动的自检会自动点亮内屏（最多重试 10 轮，等 Shizuku 连上）；
  ② 内屏恢复有多级回退（枚举 → 历史 displayId → `DEFAULT_DISPLAY`）并带重试。
  **仍未实现**「前台服务常驻」，所以极端情况下（杀进程后不再打开 App）需要手动按电源键点亮。
- `requestDisplayPower` 的签名在 API 36 发生变化，已分别处理，但未在 ColorOS 上实测。
- 逻辑尺寸覆盖通道依赖 ColorOS 未裁剪 `IWindowManager`；若被裁剪会回退到
  `wm size <WxH> -d <id>` 并在诊断信息里给出失败原因。
- 亮度 / HDR / 色彩管理不在范围内。
