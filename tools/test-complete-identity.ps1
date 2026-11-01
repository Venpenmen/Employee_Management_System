param([Parameter(Mandatory=$true)][string]$Fixture,[ValidateSet('server','portable','dev')][string]$Mode,[string]$ExpectedApp,[string]$ExpectedDatabase)
$ErrorActionPreference='Stop'
if(-not $env:EMS_DEVELOPMENT_TASK_ID -or -not(Test-Path -LiteralPath "$Fixture/test-fixture.json")){throw 'Managed fixture required'}
. "$Fixture/server-complete/runtime-common.ps1" -Mode $Mode
$state=Read-EmsState
if(($state.app.id.ToString()+':'+$state.app.ticks) -cne $ExpectedApp -or ($state.database.id.ToString()+':'+$state.database.ticks) -cne $ExpectedDatabase){throw 'Peer process identity changed'}
if($state.nativeDrive -and (Get-EmsDriveTarget $state.nativeDrive) -ne ('\??\'+$script:EmsRoot)){throw 'Peer native executable alias no longer binds this fixture'}
if(-not(Get-OwnedProcess $state.app) -or -not(Get-OwnedProcess $state.database)){throw 'Peer application/database not alive'}
Write-Host 'PASS exact peer PID, start time, canonical executable and verified native-drive alias preserved'
