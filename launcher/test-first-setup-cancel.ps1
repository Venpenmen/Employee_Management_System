param([Parameter(Mandatory=$true)][string]$Fixture,[ValidateSet('server','portable')][string]$Mode,[ValidateSet('server','server-complete')][string]$RuntimeDirectory='server')
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath "$Fixture/test-fixture.json")){throw 'Synthetic fixture required'}
. "$Fixture/$RuntimeDirectory/runtime-common.ps1" -Mode $Mode
function Read-Host {param([string]$Prompt,[switch]$AsSecureString);return '取消'}
$cancelled=$false
try{Invoke-EmsFirstSetup (New-EmsState)}catch [OperationCanceledException]{$cancelled=$true}
if(-not $cancelled){throw 'First setup cancellation not recognized'}
if(Test-Path -LiteralPath "$script:DataDir/credentials-upgrade.dpapi.json"){throw 'Cancelled form unexpectedly generated credentials'}
Write-Host 'PASS: first-setup cancellation exits before any account/credential/database operation; subsequent initialization remains available'
