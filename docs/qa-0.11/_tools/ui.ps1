param(
    [ValidateSet('texts', 'dump', 'find', 'tap', 'tabs')][string]$Action = 'texts',
    [string]$Text = '',
    [int]$TimeoutSec = 8
)
# 用法:
#   pwsh -File _tools/ui.ps1 -Action texts                 # 列出当前页面所有 text/desc + 坐标
#   pwsh -File _tools/ui.ps1 -Action find -Text '外观'      # 找文本 bounds
#   pwsh -File _tools/ui.ps1 -Action tap  -Text '外观'      # 点击文本
#   pwsh -File _tools/ui.ps1 -Action dump                   # 原始 xml 存 _raw/
#   pwsh -File _tools/ui.ps1 -Action tabs                   # 自动探测底栏 tab 文案与坐标
. "$PSScriptRoot\qa-lib.ps1"
switch ($Action) {
    'texts' { Show-UiTexts }
    'tabs' {
        $detected = @(Find-BottomTabs)
        if ($detected.Count -eq 0) {
            Write-Output 'NO BOTTOM TABS DETECTED'
            exit 2
        }
        $i = 0
        foreach ($tab in $detected) {
            Write-Output ("tab[{0}] '{1}' center=({2},{3}) bounds=[{4},{5}][{6},{7}]" -f $i, $tab.Label, $tab.CenterX, $tab.CenterY, $tab.Bounds.Left, $tab.Bounds.Top, $tab.Bounds.Right, $tab.Bounds.Bottom)
            $i++
        }
        Write-Output ("count={0}" -f $detected.Count)
    }
    'dump' {
        $doc = Get-UiDump -RawName 'ui-manual.xml'
        $hits = $doc.SelectNodes('//node').Count
        Write-Output ("dumped {0} nodes -> {1}" -f $hits, (Join-Path (Get-QaRoot) '_raw\ui-manual.xml'))
    }
    'find' {
        $hit = Find-UiTextBounds -Text $Text -TimeoutSec $TimeoutSec
        if (-not $hit) { Write-Output "NOT FOUND: $Text"; exit 2 }
        Write-Output ("FOUND '{0}' class={1} clickable={2} bounds={3} center=({4},{5})" -f $hit.Text, $hit.Class, $hit.Clickable, $hit.Bounds, $hit.CenterX, $hit.CenterY)
    }
    'tap' {
        $hit = Invoke-TapText -Text $Text -TimeoutSec $TimeoutSec
        Write-Output ("TAPPED '{0}' at ({1},{2})" -f $hit.Text, $hit.CenterX, $hit.CenterY)
    }
}
