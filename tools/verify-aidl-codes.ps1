# 验证隐藏 AIDL 的 transaction code
#
# 背景：AIDL 的约定是 `TRANSACTION_x = FIRST_CALL_TRANSACTION + 声明序号(0起始)`。
# 也就是**第一个方法 = code 1**。本项目在这一条上踩过两次坑：
# 把序号当成 1 起始，导致所有 code 整体 +1 —— transact 会「成功」，
# 但调用的是**完全不同的方法**，静默什么都不做，不抛异常。
#
# 所以本脚本不靠人眼、也不靠文本解析器猜，而是：
#   1. 从 AOSP 原版 .aidl 里按声明顺序抽出方法名；
#   2. 生成一个**同序**的骨架 AIDL，全部声明成 `void 方法名();`；
#   3. 交给**真实的 aidl.exe** 编译；
#   4. 读编译器写出的 `TRANSACTION_x = (FIRST_CALL_TRANSACTION + n)`。
# 这样得到的 code 与设备上 framework 的 AIDL 完全同源。
#
# 用法：
#   pwsh -File tools/verify-aidl-codes.ps1
#
# 需要 D:\android-sdk（或设置 ANDROID_HOME）。

param(
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\android-sdk" }),
    [string]$AidlDir = "$PSScriptRoot\aidl"
)

$ErrorActionPreference = "Stop"
$aidlExe = Get-ChildItem "$SdkRoot\build-tools" -Filter "aidl.exe" -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $aidlExe) { Write-Error "找不到 aidl.exe，请检查 -SdkRoot（当前：$SdkRoot）" }
Write-Host "使用编译器: $aidlExe`n"

$work = Join-Path $env:TEMP ("aidl-verify-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path "$work\out", "$work\pk" | Out-Null

# 需要核对的 (aidl 文件, 接口名, 目标方法列表)
$cases = @(
    @{ File = "IDisplayManager.api36.aidl"; Iface = "IDisplayManager"; Tag = "IDisplayManager API36";
       Methods = @("setUserPreferredDisplayMode", "getUserPreferredDisplayMode", "requestDisplayPower", "getDisplayInfo") },
    @{ File = "IDisplayManager.api35.aidl"; Iface = "IDisplayManager"; Tag = "IDisplayManager API35";
       Methods = @("setUserPreferredDisplayMode", "requestDisplayPower") },
    @{ File = "IDisplayManager.api34.aidl"; Iface = "IDisplayManager"; Tag = "IDisplayManager API34";
       Methods = @("setUserPreferredDisplayMode") },

    @{ File = "IWindowManager.api36.aidl"; Iface = "IWindowManager"; Tag = "IWindowManager API36";
       Methods = @("setForcedDisplaySize", "clearForcedDisplaySize", "getBaseDisplaySize", "getWindowingMode", "setWindowingMode") },
    @{ File = "IWindowManager.api35.aidl"; Iface = "IWindowManager"; Tag = "IWindowManager API35";
       Methods = @("setForcedDisplaySize", "clearForcedDisplaySize", "getBaseDisplaySize", "getWindowingMode", "setWindowingMode") },
    @{ File = "IWindowManager.api34.aidl"; Iface = "IWindowManager"; Tag = "IWindowManager API34";
       Methods = @("setForcedDisplaySize", "clearForcedDisplaySize", "getBaseDisplaySize", "getWindowingMode", "setWindowingMode") },

    @{ File = "IAudioService.api36.aidl"; Iface = "IAudioService"; Tag = "IAudioService API36";
       Methods = @("setPreferredDevicesForStrategy", "removePreferredDevicesForStrategy", "getPreferredDevicesForStrategy", "getAudioProductStrategies", "setCommunicationDevice", "getCommunicationDevice") },
    @{ File = "IAudioService.api35.aidl"; Iface = "IAudioService"; Tag = "IAudioService API35";
       Methods = @("setPreferredDevicesForStrategy", "removePreferredDevicesForStrategy", "getPreferredDevicesForStrategy", "getAudioProductStrategies", "setCommunicationDevice", "getCommunicationDevice") }
)

function Get-InterfaceMethodNames {
    param([string]$Path, [string]$Iface)
    $lines = [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -match "^interface\s+$Iface\s*\{?$") { $start = $i; break }
    }
    if ($start -lt 0) { return $null }
    $names = New-Object System.Collections.ArrayList
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        $l = $lines[$i].Trim()
        # 跳过空行 / 注释 / 注解行 / 结束花括号
        if ($l -eq '' -or $l.StartsWith('//') -or $l.StartsWith('@') -or $l -eq '}' -or $l -eq '{') { continue }
        # 匹配 "类型 方法名(" —— 支持 oneway 修饰符与 in/out 参数前缀
        $m = [regex]::Match($l, '^(?:oneway\s+)?(?:in\s+|out\s+|inout\s+)?([A-Za-z_][\w.]*(?:\[\])?(?:\s*<[^>]*>)?)\s+([A-Za-z_]\w*)\s*\(')
        if ($m.Success -and $m.Groups[2].Value -ne $Iface) { [void]$names.Add($m.Groups[2].Value) }
    }
    return $names
}

$fail = 0
foreach ($c in $cases) {
    $path = Join-Path $AidlDir $c.File
    if (-not (Test-Path $path)) { Write-Host "[跳过] $($c.Tag)：找不到 $($c.File)"; continue }

    $names = Get-InterfaceMethodNames -Path $path -Iface $c.Iface
    if (-not $names -or $names.Count -eq 0) { Write-Host "[失败] $($c.Tag)：抽不到方法名"; $fail++; continue }

    Remove-Item "$work\out\*" -Recurse -Force -ErrorAction SilentlyContinue
    $sb = "package pk;`ninterface IProbe {`n"
    foreach ($n in $names) { $sb += "    void $n();`n" }
    $sb += "}`n"
    [System.IO.File]::WriteAllText("$work\pk\IProbe.aidl", $sb)

    & $aidlExe -o"$work\out" -I"$work" "$work\pk\IProbe.aidl" 2>&1 | Out-Null
    $javaPath = "$work\out\pk\IProbe.java"
    if (-not (Test-Path $javaPath)) { Write-Host "[失败] $($c.Tag)：编译器没有产出"; $fail++; continue }
    $java = [System.IO.File]::ReadAllText($javaPath)

    Write-Host "=== $($c.Tag)（方法数 $($names.Count)）==="
    foreach ($t in $c.Methods) {
        $mm = [regex]::Match($java, "int TRANSACTION_$t = \(android\.os\.IBinder\.FIRST_CALL_TRANSACTION \+ (\d+)\)")
        if ($mm.Success) {
            Write-Host ("  {0,-36} = {1}" -f $t, ([int]$mm.Groups[1].Value + 1))
        } else {
            Write-Host ("  {0,-36} : 该方法不存在于本版本 AIDL（这本身是有用信息）" -f $t)
        }
    }
    Write-Host ""
}

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
if ($fail -gt 0) { Write-Host "有 $fail 项失败"; exit 1 }
Write-Host "全部核对完成。请把上面的值与本项目代码中的常量逐条比对。"
