param([int]$TimeoutSec = 300)
# 等待模拟器 boot_completed=1 + pm 就绪；打印设备/系统信息。
. "$PSScriptRoot\qa-lib.ps1"
$boot = Wait-EmulatorBoot -TimeoutSec $TimeoutSec
if (-not $boot.Ok) { Write-Output "BOOT FAILED: $($boot.Detail)"; exit 1 }
Write-Output ("BOOT OK in {0}s serial={1}" -f $boot.ElapsedSec, $boot.Serial)
Write-Output ("sdk=" + (Invoke-AdbText @('shell', 'getprop', 'ro.build.version.sdk')))
Write-Output ("release=" + (Invoke-AdbText @('shell', 'getprop', 'ro.build.version.release')))
Write-Output ("model=" + (Invoke-AdbText @('shell', 'getprop', 'ro.product.model')))
