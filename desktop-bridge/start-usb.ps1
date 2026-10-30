param(
    [int]$DesktopPort = 0,
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Adb)) { throw '找不到 adb，请通过 -Adb 指定 Android platform-tools 中的 adb.exe。' }
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) { throw '请先安装 uv。' }
if ($DesktopPort -eq 0) {
    $ports = @(Get-Process Xfriend -ErrorAction Stop | ForEach-Object {
        Get-NetTCPConnection -State Listen -OwningProcess $_.Id -ErrorAction SilentlyContinue |
            Where-Object LocalAddress -eq '127.0.0.1' | Select-Object -ExpandProperty LocalPort
    } | Sort-Object -Unique)
    if ($ports.Count -ne 1) { throw '无法唯一确定电脑版端口，请先启动并登录 Xfriend，或传入 -DesktopPort。' }
    $DesktopPort = $ports[0]
}
if ($DesktopPort -lt 1 -or $DesktopPort -gt 65535) { throw '电脑版端口无效。' }
$desktopAddress = "http://127.0.0.1:$DesktopPort"
$status = Invoke-RestMethod "$desktopAddress/api/desktop/auth/status" -TimeoutSec 10
if (-not $status.enabled -or $status.state -ne 'authenticated') { throw '请先在电脑版登录云账号。' }
Push-Location $PSScriptRoot
$forwarded = $false
try {
    & uv sync --frozen
    if ($LASTEXITCODE -ne 0) { throw '连接桥依赖安装失败。' }
    & $Adb reverse tcp:8765 tcp:8765
    if ($LASTEXITCODE -ne 0) { throw 'USB 转发失败，请检查手机 USB 调试授权且只连接一台设备。' }
    $forwarded = $true
    Write-Host '在 Xfriend 同步版中填写 http://127.0.0.1:8765，以及下面的配对码。Ctrl+C 停止连接。'
    & uv run --frozen bridge.py --desktop $desktopAddress
} finally {
    if ($forwarded) { & $Adb reverse --remove tcp:8765 }
    Pop-Location
}
