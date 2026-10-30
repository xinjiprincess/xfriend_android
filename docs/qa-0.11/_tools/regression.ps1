param(
    [Parameter(Mandatory)][string]$XFQa,
    [Parameter(Mandatory)][string]$XSQa
)
# 最终构建回归（5 分钟内）：P0 底栏 5 tab、P1 分段灰边、P2 底栏 80dp、崩溃检查
# 用法: & _tools\regression.ps1 -XFQa <path> -XSQa <path>
. "$PSScriptRoot\qa-lib.ps1"
$qa = Get-QaRoot
$adb = Get-AdbPath
$summary = New-Object System.Collections.Generic.List[string]
function Say([string]$s) { Write-Host $s; $summary.Add($s) }
function Get-Dump([string]$name) {
    try {
        $p = Join-Path $qa "_raw\$name.xml"
        & $adb -s emulator-5554 shell uiautomator dump /sdcard/$name.xml 2>&1 | Out-Null
        & $adb -s emulator-5554 pull /sdcard/$name.xml $p 2>&1 | Out-Null
        if (Test-Path $p) { return [xml](Get-Content -Raw -Encoding UTF8 $p) }
    } catch { }
    return $null
}
function Wait-Text([string]$needle, [int]$timeoutSec = 25) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $doc = Get-Dump 'reg-wait'
        if ($doc) {
            $hit = $doc.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -like "*$needle*" } | Select-Object -First 1
            if ($hit) { return $true }
        }
        Start-Sleep -Seconds 2
    }
    return $false
}
function Tap-Exact([string]$text, [int]$minTop = 0, [int]$maxTop = 10000) {
    $doc = Get-Dump 'reg-tap'
    if (-not $doc) { return $false }
    $n = $doc.SelectNodes('//node') | Where-Object {
        $b = $_.GetAttribute('bounds')
        $_.GetAttribute('text') -eq $text -and $b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and [int]$Matches[2] -ge $minTop -and [int]$Matches[2] -le $maxTop
    } | Select-Object -First 1
    if (-not $n) { Say "  !! text not found: $text"; return $false }
    $bb = $n.GetAttribute('bounds'); $bb -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' | Out-Null
    $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2); $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb -Arguments @('shell', 'input', 'tap', "$cx", "$cy") | Out-Null
    Say "  tap '$text' at ($cx,$cy)"
    return $true
}
function Measure-Bar([string]$pngName) {
    $py = 'C:\Users\zhang\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
    $path = Join-Path $qa "$pngName.png"
    $code = @"
from PIL import Image
im = Image.open(r'$path').convert('RGB')
h = im.size[1]
prev=None; trans=[]
for y in range(2000, h):
    c=im.getpixel((540,y)); s='#%02X%02X%02X' % c
    if s!=prev: trans.append((y,s)); prev=s
print('TRANS', trans)
row=[im.getpixel((x,h-20)) for x in range(0,1080)]
dark=[x for x,c in enumerate(row) if max(c)-min(c)<14 and sum(c)/3<160]
print('PILL', (min(dark), max(dark)) if dark else None)
"@
    return (& $py -c $code 2>&1) -join "`n"
}

Say "=== 0.11 final regression $(Get-Date -Format o) ==="
Say "XF QA: $XFQa"
Say "XS QA: $XSQa"

# 0. 安装 QA 包
foreach ($p in @($XFQa, $XSQa)) {
    $out = (& $adb -s emulator-5554 install -r -t $p 2>&1) -join ' | '
    Say "install $([System.IO.Path]::GetFileName($p)): $out"
}
foreach ($pkg in @('com.singu.xfriend.mobile.qa', 'com.singu.xsalers.mobile.qa')) {
    $d = & $adb -s emulator-5554 shell dumpsys package $pkg 2>&1
    $ver = ($d | Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
    Say "$pkg -> $ver"
}

& $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
& $adb -s emulator-5554 shell cmd uimode night no 2>&1 | Out-Null

# ========== 1. XSalers 回归：底栏 5 tab ==========
Say "`n--- [1] XSalers 底栏 / 5 tab / 外观 ---"
& $adb -s emulator-5554 shell am force-stop com.singu.xsalers.mobile.qa 2>&1 | Out-Null
& $adb -s emulator-5554 shell am start -n com.singu.xsalers.mobile.qa/com.singu.xfriend.SalesFixtureActivity 2>&1 | Out-Null
$ready = Wait-Text '今日跟进' 25
Say "content ready: $ready"
Save-Screenshot -Name 'xs-today-reg' | Out-Null
$tabs = @(Find-BottomTabs)
Say ("bottom tabs detected: {0} -> {1}" -f $tabs.Count, (($tabs | ForEach-Object { $_.Label }) -join '/'))
$tabNames = @('xs-today-reg', 'xs-customers-reg', 'xs-review-reg', 'xs-experts-reg', 'xs-profile-reg')
$okTabs = @()
if ($tabs.Count -eq 5) {
    for ($i = 1; $i -lt 5; $i++) {
        Invoke-TabTap -Index $i -Count 5 | Out-Null
        Start-Sleep -Seconds 3
        Save-Screenshot -Name $tabNames[$i] | Out-Null
        $doc = Get-Dump "reg-tab$i"
        $title = if ($doc) { ($doc.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -in @('今日','客户','复盘','专家','我的') -and $_.GetAttribute('bounds') -match '\[(\d+),(\d+)\]' } | Select-Object -First 1).GetAttribute('text') } else { '?' }
        $okTabs += "$i=$title"
        Say ("  tab[{0}] tapped -> screenshot {1}.png, first-title={2}" -f $i, $tabNames[$i], $title)
    }
} else { Say "  !! P0 NOT FIXED: tab count = $($tabs.Count), expect 5" }

# 外观（在我的 tab 下）
Say "  -- 外观 entry --"
if (Tap-Exact '外观') {
    Start-Sleep -Seconds 3
    Save-Screenshot -Name 'xs-appearance-reg-light' | Out-Null
    Say "  appearance page screenshot: xs-appearance-reg-light.png"
    if (Tap-Exact '深色') {
        Start-Sleep -Seconds 3
        Save-Screenshot -Name 'xs-appearance-reg-dark' | Out-Null
        Say "  appearance dark screenshot: xs-appearance-reg-dark.png"
    }
} else { Say "  !! appearance entry missing" }
& $adb -s emulator-5554 shell input keyevent 4 2>&1 | Out-Null

# ========== 2. Xfriend 回归：底栏 80dp + 分段 ==========
Say "`n--- [2] Xfriend 底栏 80dp / 分段控件 ---"
& $adb -s emulator-5554 shell am force-stop com.singu.xsalers.mobile.qa 2>&1 | Out-Null
& $adb -s emulator-5554 shell am force-stop com.singu.xfriend.mobile.qa 2>&1 | Out-Null
& $adb -s emulator-5554 shell am start -n com.singu.xfriend.mobile.qa/com.singu.xfriend.SalesFixtureActivity 2>&1 | Out-Null
$readyXf = Wait-Text 'Xfriend 助手' 25
Say "Xfriend content ready: $readyXf"
Save-Screenshot -Name 'xf-chat-reg' | Out-Null
$xfTabs = @(Find-BottomTabs)
Say ("Xfriend bottom tabs: {0} -> {1}" -f $xfTabs.Count, (($xfTabs | ForEach-Object { $_.Label }) -join '/'))
Invoke-TabTap -Index 1 -Count 4 | Out-Null
Start-Sleep -Seconds 3
Save-Screenshot -Name 'xf-workbench-reg' | Out-Null
Say "  workbench screenshot: xf-workbench-reg.png (检查分段「专家」灰边)"
$barMetrics = Measure-Bar 'xf-chat-reg'
Say "  bar pixel metrics:"
$barMetrics -split "`n" | ForEach-Object { Say ("    " + $_) }
$barDoc = Get-Dump 'reg-bar'
if ($barDoc) {
    $bottom = $barDoc.SelectNodes('//node') | ForEach-Object {
        $b = $_.GetAttribute('bounds')
        if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and [int]$Matches[2] -gt 2000) {
            [pscustomobject]@{ text = $_.GetAttribute('text'); bounds = $b; top = [int]$Matches[2]; bottomY = [int]$Matches[4] }
        }
    }
    $labels = $bottom | Where-Object { $_.text -in @('对话', '工作台', '资源', '我的') }
    $labels | ForEach-Object { Say ("    label '{0}' bounds={1}" -f $_.text, $_.bounds) }
}

# ========== 3. logcat ==========
Say "`n--- [3] logcat 崩溃/ANR ---"
$log = Invoke-AdbText -Arguments @('logcat', '-d', '-v', 'time') -AllowFailure
$log | Set-Content -Encoding UTF8 (Join-Path $qa '_logs\logcat-final-regression.txt')
$fatal = ($log -split "`n") | Where-Object { $_ -match 'FATAL EXCEPTION|AndroidRuntime.*FATAL|ANR in|am_anr|Process: com\.singu' }
Say ("fatal/anr matches: {0}" -f @($fatal).Count)
@($fatal) | Select-Object -First 10 | ForEach-Object { Say ("    " + $_) }

Say "`n=== regression done $(Get-Date -Format o) ==="
$summary | Set-Content -Encoding UTF8 (Join-Path $qa '_logs\regression-summary.txt')
Write-Output ("summary -> " + (Join-Path $qa '_logs\regression-summary.txt'))
