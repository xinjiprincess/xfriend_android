param(
    [int]$Width = 1080,
    [int]$Height = 2400,
    [int]$Density = 0,
    [switch]$Reset,
    [switch]$Status
)
# 项目 AVD XfriendSuiteQA 是 Pixel Tablet 面板(2560x1600 landscape)；
# 为使视觉验收接近手机形态，用 wm size/density 覆盖成竖屏手机分辨率（仅设备设置，不改源码）。
# 用法:
#   pwsh -File _tools/phone-size.ps1                 # 应用 1080x2400，锁竖屏，截一张验证图
#   pwsh -File _tools/phone-size.ps1 -Reset          # 恢复物理分辨率
#   pwsh -File _tools/phone-size.ps1 -Status         # 只打印当前分辨率/密度
. "$PSScriptRoot\qa-lib.ps1"
if ($Status) {
    $s = Get-ScreenSize -Force
    Write-Output ("wm size    : {0}x{1}" -f $s.Width, $s.Height)
    Write-Output ("wm density : {0}" -f (Get-ScreenDensity -Force))
    exit 0
}
if ($Reset) {
    Invoke-Adb -Arguments @('shell', 'wm', 'size', 'reset') | Out-Null
    Invoke-Adb -Arguments @('shell', 'wm', 'density', 'reset') | Out-Null
    Write-Output "wm size/density reset to physical."
} else {
    Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
    Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'user_rotation', '0') | Out-Null
    Invoke-Adb -Arguments @('shell', 'wm', 'size', ("{0}x{1}" -f $Width, $Height)) | Out-Null
    if ($Density -gt 0) { Invoke-Adb -Arguments @('shell', 'wm', 'density', $Density) | Out-Null }
    Write-Output ("applied wm size {0}x{1}" -f $Width, $Height)
}
Start-Sleep -Seconds 3
$s = Get-ScreenSize -Force
Write-Output ("wm size now: {0}x{1}  density={2}" -f $s.Width, $s.Height, (Get-ScreenDensity -Force))
if (-not $Reset) {
    $shot = Save-Screenshot -Name 'wm-size-check'
    $py = 'C:\Users\zhang\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
    if (Test-Path $py) {
        $info = & $py -c "from PIL import Image; im=Image.open(r'$($shot.Path)'); print(im.size)" 2>&1
        Write-Output ("screencap actual pixels: {0}" -f ($info -join ' '))
    }
}
