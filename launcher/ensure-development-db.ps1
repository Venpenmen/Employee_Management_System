$ErrorActionPreference='Stop'
. "$PSScriptRoot\portable-common.ps1"
$lock=$null
try {
  $lock=Enter-EmsLock
  Assert-Package
  $state=Read-EmsState
  if(-not $state){$state=New-EmsState}
  Mount-EmsNativeRoot $state
  Save-EmsState $state
  Initialize-EmsDatabase
  if(-not (Get-OwnedProcess $state.database)) {
    $state.dbPort=Find-FreePort 13306 13320 $state.dbPort
    Save-EmsState $state
    Start-EmsDatabase $state
  }
  Wait-EmsDatabase $state
  Restore-EmsSnapshot $state
  # Schema upgrade is idempotent and protected by the launcher lock.
  Update-EmsProfileSchema $state
  Write-Host "Development database ready on port $($state.dbPort)."
} catch {
  Write-Error "Development database startup failed: $($_.Exception.Message)"
  exit 1
} finally {if($lock){$lock.Dispose()}}
