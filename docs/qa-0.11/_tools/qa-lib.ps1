<#
  qa-lib.ps1 - 0.11 Android 视觉/运行验收公共函数库
  只被 docs/qa-0.11/_tools/ 下的脚本 dot-source；不修改任何源码。
  用法:  . "$PSScriptRoot\qa-lib.ps1"
#>
$ErrorActionPreference = 'Stop'

$script:QaRoot = Split-Path -Parent $PSScriptRoot
$script:AdbExe = if ($env:QA_ADB) { $env:QA_ADB } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
$script:RawDir = Join-Path $script:QaRoot '_raw'
$script:LogDir = Join-Path $script:QaRoot '_logs'
foreach ($d in @($script:RawDir, $script:LogDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
}

$script:KnownTabs = @{
    xfriend = @('对话', '工作台', '资源', '我的')
    xsalers = @('今日', '客户', '复盘', '专家', '我的')
}

function Get-QaRoot { return $script:QaRoot }
function Get-AdbPath { return $script:AdbExe }

function Get-AdbSerial {
    if ($script:Serial) { return $script:Serial }
    # PS5.1: 原生 stderr 被 2>&1 重定向会变 ErrorRecord，EAP=Stop 时会误抛，临时降级
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & $script:AdbExe devices 2>&1 } finally { $ErrorActionPreference = $prevEap }
    $line = ($out | Where-Object { "$_" -match '^(\S+)\s+device\b' } | Select-Object -First 1)
    if (-not $line) { throw "No adb device in 'device' state. adb devices output: $(($out | ForEach-Object { "$_" }) -join '; ')" }
    $script:Serial = ([regex]::Match("$line", '^(\S+)')).Groups[1].Value
    return $script:Serial
}

<#
  Invoke-Adb -Arguments @('shell','wm','size') [-AllowFailure] [-Quiet]
  返回 stdout 行数组（stderr 合并）。失败且未 AllowFailure 时抛错。
  注意: adb pull 等把进度写 stderr，PS5.1 下必须临时把 EAP 设为 Continue。
#>
function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure,
        [switch]$Quiet
    )
    $serial = Get-AdbSerial
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:AdbExe -s $serial @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    $text = ($out | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed (exit $code): $text"
    }
    return , $out
}

function Invoke-AdbText {
    param([Parameter(Mandatory)][string[]]$Arguments, [switch]$AllowFailure)
    $out = Invoke-Adb -Arguments $Arguments -AllowFailure:$AllowFailure
    return (($out | ForEach-Object { "$_" }) -join "`n").Trim()
}

function Wait-EmulatorBoot {
    param([int]$TimeoutSec = 300, [int]$PollSec = 5)
    $start = Get-Date
    $deadline = $start.AddSeconds($TimeoutSec)
    $lastState = 'unknown'
    while ((Get-Date) -lt $deadline) {
        try {
            $serial = Get-AdbSerial
            $boot = Invoke-AdbText -Arguments @('shell', 'getprop', 'sys.boot_completed') -AllowFailure
            $lastState = "device=$serial boot_completed='$boot'"
            if ($boot -eq '1') {
                # 等 package manager / window manager 就绪
                $pm = Invoke-AdbText -Arguments @('shell', 'pm', 'path', 'android') -AllowFailure
                if ($pm -match 'package:') {
                    $elapsed = [int]((Get-Date) - $start).TotalSeconds
                    return [pscustomobject]@{ Ok = $true; ElapsedSec = $elapsed; Serial = $serial; Detail = $lastState }
                }
            }
        } catch {
            $lastState = "waiting: $($_.Exception.Message)"
        }
        Start-Sleep -Seconds $PollSec
    }
    return [pscustomobject]@{ Ok = $false; ElapsedSec = $TimeoutSec; Serial = $null; Detail = $lastState }
}

function Get-ScreenSize {
    param([switch]$Force)
    if ($script:ScreenSize -and -not $Force) { return $script:ScreenSize }
    $text = Invoke-AdbText -Arguments @('shell', 'wm', 'size')
    # 优先 Override size（若有），否则 Physical size
    $m = [regex]::Match($text, 'Override size:\s*(\d+)x(\d+)')
    if (-not $m.Success) { $m = [regex]::Match($text, 'Physical size:\s*(\d+)x(\d+)') }
    if (-not $m.Success) { throw "Cannot parse 'wm size' output: $text" }
    $script:ScreenSize = [pscustomobject]@{ Width = [int]$m.Groups[1].Value; Height = [int]$m.Groups[2].Value; Raw = $text }
    return $script:ScreenSize
}

function Get-ScreenDensity {
    param([switch]$Force)
    if ($script:Density -and -not $Force) { return $script:Density }
    $text = Invoke-AdbText -Arguments @('shell', 'wm', 'density')
    $m = [regex]::Match($text, 'Physical density:\s*(\d+)')
    if (-not $m.Success) { $m = [regex]::Match($text, 'Override density:\s*(\d+)') }
    if (-not $m.Success) { throw "Cannot parse 'wm density' output: $text" }
    $script:Density = [int]$m.Groups[1].Value
    return $script:Density
}

function Get-DpScale { return (Get-ScreenDensity) / 160.0 }

<#
  Save-Screenshot -Name 'xf-chat-light' [-Subdir ''] [-WaitSec 0]
  adb shell screencap -p -> /sdcard/qa_shot.png -> adb pull -> <qa>/<name>.png
  校验 PNG 魔数与大小，不做任何伪造/占位图。
#>
function Save-Screenshot {
    param(
        [Parameter(Mandatory)][string]$Name,
        [string]$Subdir = '',
        [int]$WaitSec = 0,
        [int]$MinBytes = 4096
    )
    if ($WaitSec -gt 0) { Start-Sleep -Seconds $WaitSec }
    $dir = if ($Subdir) { Join-Path $script:QaRoot $Subdir } else { $script:QaRoot }
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $local = Join-Path $dir ($Name + '.png')
    $remote = '/sdcard/qa_shot.png'
    $lastErr = ''
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-Adb -Arguments @('shell', 'screencap', '-p', $remote) -AllowFailure | Out-Null
            Invoke-Adb -Arguments @('pull', $remote, $local) | Out-Null
            if (Test-Path $local) {
                $len = (Get-Item $local).Length
                if ($len -ge $MinBytes) {
                    # Windows PowerShell 5.1 兼容：不能用 -AsByteStream
                    $sig = [System.IO.File]::ReadAllBytes($local)
                    $expected = [byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                    $ok = ($sig.Length -ge 8)
                    if ($ok) {
                        for ($i = 0; $i -lt 8; $i++) { if ($sig[$i] -ne $expected[$i]) { $ok = $false; break } }
                    }
                    if ($ok) {
                        return [pscustomobject]@{ Ok = $true; Path = $local; Bytes = $len; Attempt = $attempt }
                    }
                    $lastErr = "bad PNG signature (size=$len)"
                } else {
                    $lastErr = "file too small ($len bytes)"
                }
            } else {
                $lastErr = 'pull produced no file'
            }
        } catch {
            $lastErr = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    throw "screencap '$Name' failed after 3 attempts: $lastErr"
}

<#
  Get-UiDump [-RawName] -> [xml] 的 uiautomator dump 文档，并把原始 xml 存到 _raw/
#>
function Get-UiDump {
    param([string]$RawName = 'ui-dump.xml')
    $remote = '/sdcard/qa_ui.xml'
    Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', $remote) -AllowFailure | Out-Null
    $local = Join-Path $script:RawDir $RawName
    Invoke-Adb -Arguments @('pull', $remote, $local) | Out-Null
    if (-not (Test-Path $local)) { throw "uiautomator dump failed: no local file $local" }
    return [xml](Get-Content -Raw -Encoding UTF8 $local)
}

function ConvertFrom-Bounds {
    param([string]$Bounds)
    $m = [regex]::Match($Bounds, '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    if (-not $m.Success) { return $null }
    $l = [int]$m.Groups[1].Value; $t = [int]$m.Groups[2].Value
    $r = [int]$m.Groups[3].Value; $b = [int]$m.Groups[4].Value
    return [pscustomobject]@{
        Left = $l; Top = $t; Right = $r; Bottom = $b
        Width = $r - $l; Height = $b - $t
        CenterX = [int](($l + $r) / 2); CenterY = [int](($t + $b) / 2)
    }
}

<#
  Find-UiTextBounds -Text '我的' [-TimeoutSec 8] [-Quiet]
  在 uiautomator dump 里找 text 或 content-desc 包含该字符串的节点，返回中心点/bounds。
#>
function Find-UiTextBounds {
    param(
        [Parameter(Mandatory)][string]$Text,
        [int]$TimeoutSec = 8,
        [int]$PollSec = 1,
        [switch]$Quiet
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        try {
            $doc = Get-UiDump -RawName 'ui-find.xml'
            foreach ($n in $doc.SelectNodes('//node')) {
                $t = $n.GetAttribute('text')
                $d = $n.GetAttribute('content-desc')
                if (($t -and $t -like "*$Text*") -or ($d -and $d -like "*$Text*")) {
                    $b = ConvertFrom-Bounds $n.GetAttribute('bounds')
                    if ($b) {
                        return [pscustomobject]@{
                            Text = $t; Desc = $d; Class = $n.GetAttribute('class')
                            Clickable = $n.GetAttribute('clickable'); Bounds = $b
                            CenterX = $b.CenterX; CenterY = $b.CenterY
                        }
                    }
                }
            }
        } catch {
            if (-not $Quiet) { Write-Host "  (ui dump retry: $($_.Exception.Message))" }
        }
        Start-Sleep -Seconds $PollSec
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Show-UiTexts {
    param([string]$RawName = 'ui-texts.xml')
    $doc = Get-UiDump -RawName $RawName
    $rows = foreach ($n in $doc.SelectNodes('//node')) {
        $t = $n.GetAttribute('text'); $d = $n.GetAttribute('content-desc')
        if ($t -or $d) {
            $b = ConvertFrom-Bounds $n.GetAttribute('bounds')
            [pscustomobject]@{
                text = $t; desc = $d; class = $n.GetAttribute('class')
                clickable = $n.GetAttribute('clickable')
                bounds = $n.GetAttribute('bounds')
                cx = if ($b) { $b.CenterX } else { $null }
                cy = if ($b) { $b.CenterY } else { $null }
            }
        }
    }
    $rows | Format-Table -AutoSize | Out-String -Width 240
}

<#
  Get-TabTapPoint -Index 0 -Count 4 [-YFromBottomDp 60]
  按 wm size 等分：x = W*(i+0.5)/N，y = H - 60dp（Lead 指定的经验值）。
#>
function Get-TabTapPoint {
    param([Parameter(Mandatory)][int]$Index, [Parameter(Mandatory)][int]$Count, [double]$YFromBottomDp = 60)
    $size = Get-ScreenSize
    $scale = Get-DpScale
    $x = [int][math]::Round($size.Width * ($Index + 0.5) / $Count)
    $y = [int][math]::Round($size.Height - $YFromBottomDp * $scale)
    return [pscustomobject]@{ X = $x; Y = $y; Width = $size.Width; Height = $size.Height; DpScale = [math]::Round($scale, 3) }
}

<#
  Find-BottomTabs [-RawName]
  不依赖具体文案：在屏幕底部 10% 区域找短文本节点，按 x 排序返回底栏 tab 列表。
  0.11 的底部 tab 文案可能变化（Xfriend 4 项 / Xsalers 5 项），自动探测更稳。
#>
function Find-BottomTabs {
    param([string]$RawName = 'ui-bottom-tabs.xml')
    $doc = Get-UiDump -RawName $RawName
    $size = Get-ScreenSize
    $minTop = [int]($size.Height * 0.90)
    $tabs = New-Object System.Collections.Generic.List[object]
    foreach ($n in $doc.SelectNodes('//node')) {
        $t = $n.GetAttribute('text')
        $d = $n.GetAttribute('content-desc')
        if ($t) { $label = $t } elseif ($d) { $label = $d } else { continue }
        if ($label.Length -gt 8) { continue }
        $b = ConvertFrom-Bounds $n.GetAttribute('bounds')
        if (-not $b) { continue }
        if ($b.Top -lt $minTop) { continue }
        if ($b.Height -lt 18 -or $b.Height -gt 120) { continue }
        if ($b.Width -gt ($size.Width * 0.45)) { continue }
        $dup = $false
        foreach ($existing in $tabs) {
            if ([math]::Abs($existing.CenterX - $b.CenterX) -le 24 -and [math]::Abs($existing.CenterY - $b.CenterY) -le 40) { $dup = $true; break }
        }
        if ($dup) { continue }
        $tabs.Add([pscustomobject]@{ Label = $label; CenterX = $b.CenterX; CenterY = $b.CenterY; Bounds = $b })
    }
    return @($tabs | Sort-Object CenterX)
}

<#
  Invoke-TabTap -Index 0 -App xfriend [-Count 4] [-YFromBottomDp 60]
  点击顺序：① 已知 tab 文案精确点击 → ② 底栏文案自动探测（数量匹配时按 x 序点击）
  → ③ 按 wm size 等分坐标兜底。返回实际点击点与命中方式，便于写进报告。
#>
function Invoke-TabTap {
    param(
        [Parameter(Mandatory)][int]$Index,
        [string]$App = '',
        [int]$Count = 0,
        [string[]]$Labels = $null,
        [double]$YFromBottomDp = 60
    )
    if (-not $Labels -and $App) { $Labels = $script:KnownTabs[$App] }
    if ($Count -le 0) { $Count = if ($Labels) { $Labels.Count } else { throw 'Invoke-TabTap: need -Count or -App' } }
    if ($Index -lt 0 -or $Index -ge $Count) { throw "Invoke-TabTap: index $Index out of 0..$($Count - 1)" }

    # ① 已知文案
    if ($Labels -and $Labels.Count -gt $Index) {
        $label = $Labels[$Index]
        $hit = Find-UiTextBounds -Text $label -TimeoutSec 4 -Quiet
        if ($hit) {
            Invoke-Adb -Arguments @('shell', 'input', 'tap', "$($hit.CenterX)", "$($hit.CenterY)") | Out-Null
            return [pscustomobject]@{ Method = 'ui-text'; Label = $label; X = $hit.CenterX; Y = $hit.CenterY; Count = $Count }
        }
    }

    # ② 底栏自动探测
    try {
        $detected = @(Find-BottomTabs)
        if ($detected.Count -eq $Count) {
            $tab = $detected[$Index]
            Invoke-Adb -Arguments @('shell', 'input', 'tap', "$($tab.CenterX)", "$($tab.CenterY)") | Out-Null
            return [pscustomobject]@{ Method = 'bottom-tabs'; Label = $tab.Label; X = $tab.CenterX; Y = $tab.CenterY; Count = $Count }
        }
    } catch { }

    # ③ 等分坐标兜底
    $pt = Get-TabTapPoint -Index $Index -Count $Count -YFromBottomDp $YFromBottomDp
    Invoke-Adb -Arguments @('shell', 'input', 'tap', "$($pt.X)", "$($pt.Y)") | Out-Null
    return [pscustomobject]@{ Method = 'computed'; Label = if ($Labels) { $Labels[$Index] } else { '' }; X = $pt.X; Y = $pt.Y; Count = $Count }
}

function Invoke-TapText {
    param([Parameter(Mandatory)][string]$Text, [int]$TimeoutSec = 8, [switch]$LongPress)
    $hit = Find-UiTextBounds -Text $Text -TimeoutSec $TimeoutSec
    if (-not $hit) { throw "Text not found on screen: '$Text'" }
    if ($LongPress) {
        Invoke-Adb -Arguments @('shell', 'input', 'swipe', "$($hit.CenterX)", "$($hit.CenterY)", "$($hit.CenterX)", "$($hit.CenterY)", '800') | Out-Null
    } else {
        Invoke-Adb -Arguments @('shell', 'input', 'tap', "$($hit.CenterX)", "$($hit.CenterY)") | Out-Null
    }
    return $hit
}

function Start-App {
    param([Parameter(Mandatory)][string]$Component, [switch]$ColdStart, [int]$WaitSec = 4)
    if ($ColdStart) {
        $pkg = $Component.Split('/')[0]
        Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $pkg) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 800
    }
    $out = Invoke-AdbText -Arguments @('shell', 'am', 'start', '-W', '-n', $Component) -AllowFailure
    if ($WaitSec -gt 0) { Start-Sleep -Seconds $WaitSec }
    return $out
}

function Get-CurrentFocus {
    $out = Invoke-AdbText -Arguments @('shell', 'dumpsys', 'window', 'displays') -AllowFailure
    $m = [regex]::Match($out, 'mCurrentFocus=([^\r\n]+)')
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
    $out2 = Invoke-AdbText -Arguments @('shell', 'dumpsys', 'activity', 'activities') -AllowFailure
    $m2 = [regex]::Match($out2, 'mResumedActivity[^\r\n]*')
    if ($m2.Success) { return $m2.Value.Trim() }
    return ''
}

function Get-LogcatFatal {
    param([string]$OutFile = '')
    $out = Invoke-AdbText -Arguments @('logcat', '-d', '-v', 'time') -AllowFailure
    if ($OutFile) {
        $path = Join-Path $script:LogDir $OutFile
        $out | Set-Content -Encoding UTF8 $path
    }
    $lines = $out -split "`n"
    $hits = $lines | Where-Object {
        $_ -match 'FATAL EXCEPTION|AndroidRuntime.*(FATAL|E )|ANR in|am_anr|Force finishing|Process: com\.singu' -or
        $_ -match '\bANR\b' -and $_ -match 'com\.singu'
    }
    return [pscustomobject]@{ AllLines = $lines; Hits = @($hits); LogPath = if ($OutFile) { Join-Path $script:LogDir $OutFile } else { '' } }
}
