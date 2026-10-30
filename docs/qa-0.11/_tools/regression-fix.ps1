param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
. "$PSScriptRoot\qa-lib.ps1"

$qa = Get-QaRoot
function Dump([string]$n) {
    try {
        & $Adb -s emulator-5554 shell uiautomator dump /sdcard/$n.xml 2>&1 | Out-Null
        & $Adb -s emulator-5554 pull /sdcard/$n.xml "$qa\_raw\$n.xml" 2>&1 | Out-Null
        if (Test-Path "$qa\_raw\$n.xml") { return [xml](Get-Content -Raw -Encoding UTF8 "$qa\_raw\$n.xml") }
    } catch { }
    return $null
}
function TapExact([string]$text, [int]$minTop = 0) {
    $d = Dump 'rg-tap'
    if (-not $d) { return $false }
    $n = $d.SelectNodes('//node') | Where-Object {
        $b = $_.GetAttribute('bounds')
        $_.GetAttribute('text') -eq $text -and $b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and [int]$Matches[2] -ge $minTop
    } | Select-Object -First 1
    if (-not $n) { Write-Host "  NOT FOUND '$text'"; return $false }
    $b = $n.GetAttribute('bounds'); $b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' | Out-Null
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2); $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $Adb -s emulator-5554 shell input tap $x $y 2>&1 | Out-Null
    Write-Host "  tapped '$text' ($x,$y)"
    return $true
}

Write-Host "=== XSalers: launch + wait content ==="
& $Adb -s emulator-5554 shell am force-stop com.singu.xfriend.mobile.qa 2>&1 | Out-Null
& $Adb -s emulator-5554 shell am force-stop com.singu.xsalers.mobile.qa 2>&1 | Out-Null
& $Adb -s emulator-5554 shell am start -n com.singu.xsalers.mobile.qa/com.singu.xfriend.SalesFixtureActivity 2>&1 | Out-Null
$ok = $false
for ($i = 0; $i -lt 12; $i++) {
    Start-Sleep -Seconds 2
    $d = Dump 'rg-xs-wait'
    if ($d -and ($d.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -like '*今日跟进*' })) { $ok = $true; Write-Host "content ready after $((($i + 1) * 2))s"; break }
}
if (-not $ok) { Write-Host "WARN content not detected, continuing" }
Save-Screenshot -Name 'xs-today-reg' | Out-Null

Write-Host "=== XSalers root bottom-bar nodes ==="
$root = Dump 'rg-xs-root'
$root.SelectNodes('//node') | ForEach-Object { $b = $_.GetAttribute('bounds'); if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and [int]$Matches[2] -gt 2100) { Write-Host ("text='{0}' clickable={1} bounds={2}" -f $_.GetAttribute('text'), $_.GetAttribute('clickable'), $b) } }

Write-Host "=== go 我的 tab (index4) ==="
Invoke-TabTap -Index 4 -Count 5 | Out-Null
Start-Sleep -Seconds 3
Save-Screenshot -Name 'xs-profile-reg' | Out-Null
$prof = Dump 'rg-xs-prof'
$prof.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -ne '' } | ForEach-Object { Write-Host ("  text='{0}' bounds={1}" -f $_.GetAttribute('text'), $_.GetAttribute('bounds')) }

Write-Host "=== find/tap 外观 (scroll if needed) ==="
$found = TapExact '外观'
if (-not $found) {
    for ($i = 0; $i -lt 4 -and -not $found; $i++) {
        & $Adb -s emulator-5554 shell input swipe 540 1900 540 900 250 2>&1 | Out-Null
        Start-Sleep -Seconds 1
        $found = TapExact '外观'
    }
}
if ($found) {
    Start-Sleep -Seconds 3
    Save-Screenshot -Name 'xs-appearance-reg-light' | Out-Null
    Write-Host "  appearance light captured"
    if (TapExact '深色') {
        Start-Sleep -Seconds 3
        Save-Screenshot -Name 'xs-appearance-reg-dark' | Out-Null
        Write-Host "  appearance dark captured"
    }
} else { Write-Host "  !! 外观 still not found" }
& $Adb -s emulator-5554 shell input keyevent 4 2>&1 | Out-Null

Write-Host "=== Xfriend: launch + bar labels ==="
& $Adb -s emulator-5554 shell am force-stop com.singu.xsalers.mobile.qa 2>&1 | Out-Null
& $Adb -s emulator-5554 shell am start -n com.singu.xfriend.mobile.qa/com.singu.xfriend.SalesFixtureActivity 2>&1 | Out-Null
Start-Sleep -Seconds 8
Save-Screenshot -Name 'xf-chat-reg' | Out-Null
$xfbar = Dump 'rg-xf-bar'
$xfbar.SelectNodes('//node') | ForEach-Object { $b = $_.GetAttribute('bounds'); if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and [int]$Matches[2] -gt 2000) { Write-Host ("text='{0}' clickable={1} bounds={2}" -f $_.GetAttribute('text'), $_.GetAttribute('clickable'), $b) } }
Invoke-TabTap -Index 1 -Count 4 | Out-Null
Start-Sleep -Seconds 3
Save-Screenshot -Name 'xf-workbench-reg' | Out-Null
Write-Host "=== files ==="
Get-ChildItem "$qa\*reg*.png" | Select-Object Name, Length | Format-Table -AutoSize
