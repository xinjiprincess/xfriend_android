param(
    [Parameter(Mandatory)][string]$Name,
    [string]$Subdir = '',
    [int]$WaitSec = 0
)
# 用法: pwsh -File _tools/shot.ps1 -Name xf-chat-light [-WaitSec 2]
. "$PSScriptRoot\qa-lib.ps1"
$result = Save-Screenshot -Name $Name -Subdir $Subdir -WaitSec $WaitSec
Write-Output ("OK  {0}  ({1:N0} bytes)" -f $result.Path, $result.Bytes)
