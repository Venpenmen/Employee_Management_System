$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\portable-common.ps1"
$lock = $null
try {
    $lock = Enter-EmsLock
    $state = Read-EmsState
    if (-not $state) { Write-Host 'This portable EMS instance is not running.'; exit 0 }
    if (Get-OwnedProcess $state.database) { Mount-EmsNativeRoot $state }
    Stop-EmsApplication $state
    Stop-EmsDatabase $state
    Dismount-EmsNativeRoot $state
    Save-EmsState $state
    Write-EmsLog 'Portable EMS stopped. Data has been preserved.'
} catch {
    Write-EmsLog "STOP FAILED: $($_.Exception.Message)"
    exit 1
} finally { if ($lock) { $lock.Dispose() } }
