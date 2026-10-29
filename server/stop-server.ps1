param([ValidateSet('server','portable','dev')][string]$Mode='server')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$lock=$null
try{
 $lock=Enter-EmsLock;$state=Read-EmsState
 if($state){Mount-EmsNativeRoot $state;Stop-EmsApplication $state;Stop-EmsDatabase $state;Dismount-EmsNativeRoot $state;Save-EmsState $state}
 Write-Host "$Mode stopped";exit 0
}catch{Write-Error "Stop incomplete; retained data/state. $($_.Exception.Message)" -ErrorAction Continue;exit 2}
finally{Clear-ClientConfigs;if($lock){$lock.Dispose()}}
