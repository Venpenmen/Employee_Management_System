param([ValidateSet('server','portable','dev')][string]$Mode='server')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$s=Read-EmsState
if(-not $s){Write-Host "$Mode not initialized";exit 1}
[pscustomobject]@{version=$s.version;mode=$Mode;address=$s.address;httpPort=$s.httpPort;applicationRunning=[bool](Get-OwnedProcess $s.app);databaseRunning=[bool](Get-OwnedProcess $s.database);ready=(Test-EmsReady $s)}|Format-List
