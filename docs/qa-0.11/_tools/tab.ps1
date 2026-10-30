param(
    [Parameter(Mandatory)][int]$Index,
    [string]$App = '',
    [int]$Count = 0,
    [string[]]$Labels = $null,
    [double]$YFromBottomDp = 60,
    [int]$WaitSec = 2
)
# 用法: pwsh -File _tools/tab.ps1 -App xsalers -Index 2
#       底栏 4/5 项：优先 uiautomator 文案精确点击，其次底栏自动探测，最后按 wm size 等分坐标兜底。
. "$PSScriptRoot\qa-lib.ps1"
$r = Invoke-TabTap -Index $Index -App $App -Count $Count -Labels $Labels -YFromBottomDp $YFromBottomDp
Write-Output ("TAP index={0}/{1} label='{2}' method={3} at ({4},{5})" -f $Index, $r.Count, $r.Label, $r.Method, $r.X, $r.Y)
if ($WaitSec -gt 0) { Start-Sleep -Seconds $WaitSec }
