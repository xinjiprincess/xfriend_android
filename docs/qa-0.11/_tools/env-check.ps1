param(
    [Parameter(Mandatory)][string]$OutPng = 'setup-boot.png',
    [switch]$Setup
)
# 环境自检: 设备 / 分辨率 / 密度 / screencap+pull 链路 / 截图统计
. "$PSScriptRoot\qa-lib.ps1"
$log = Join-Path (Join-Path (Get-QaRoot) '_logs') 'env-check.txt'
$lines = New-Object System.Collections.Generic.List[string]
function Say([string]$s) { Write-Host $s; $lines.Add($s) }
function Run([string]$label, [string[]]$adbArgs) {
    try {
        $out = (Invoke-AdbText -Arguments $adbArgs -AllowFailure)
        Say ("{0,-22}: {1}" -f $label, ($out -replace "`r?`n", ' | '))
        return $out
    } catch {
        Say ("{0,-22}: ERROR {1}" -f $label, $_.Exception.Message)
        return $null
    }
}
Say "=== 0.11 QA env-check  $(Get-Date -Format o) ==="
Say ("adb path              : " + (Get-AdbPath))
Say ("qa root               : " + (Get-QaRoot))
$null = Run 'adb devices' @('devices')
$boot = Wait-EmulatorBoot -TimeoutSec 180
Say ("boot                  : Ok={0} elapsed={1}s detail={2}" -f $boot.Ok, $boot.ElapsedSec, $boot.Detail)
if (-not $boot.Ok) { $lines | Set-Content -Encoding UTF8 $log; exit 1 }
$size = Get-ScreenSize
Say ("wm size               : {0}x{1}   (raw: {2})" -f $size.Width, $size.Height, ($size.Raw -replace "`r?`n", ' | '))
$density = Get-ScreenDensity
Say ("wm density            : {0} (dp scale {1})" -f $density, (Get-DpScale))
foreach ($p in @(@('ro.build.version.release', 'android release'), @('ro.build.version.sdk', 'sdk'), @('ro.product.model', 'model'))) {
    Say ("{0,-22}: {1}" -f $p[1], (Invoke-AdbText @('shell', 'getprop', $p[0])))
}
if ($Setup) {
    # 屏幕常亮 + 解锁 + 关闭动画，保证截图稳定（仅设备设置，不改源码）
    Invoke-Adb @('shell', 'svc', 'power', 'stayon', 'true') -AllowFailure | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'screen_off_timeout', '2147483647') -AllowFailure | Out-Null
    Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Invoke-Adb @('shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
    foreach ($k in @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')) {
        Invoke-Adb @('shell', 'settings', 'put', 'global', $k, '0') -AllowFailure | Out-Null
    }
    Start-Sleep -Seconds 2
    Say "setup                 : stayon=true, keyguard dismissed, animations=0"
}
$shot = $null
try {
    $shot = Save-Screenshot -Name ([System.IO.Path]::GetFileNameWithoutExtension($OutPng)) -MinBytes 4096
    Say ("screencap+pull        : OK {0} ({1:N0} bytes)" -f $shot.Path, $shot.Bytes)
} catch {
    Say ("screencap+pull        : FAILED " + $_.Exception.Message)
}
$lines | Set-Content -Encoding UTF8 $log
Write-Output ("--- env-check log: " + $log)
if ($shot) { Write-Output ("--- screenshot: " + $shot.Path) }
