param([Parameter(Mandatory=$true)][string]$Fixture,[ValidateSet('portable','server')][string]$Mode)
$ErrorActionPreference='Stop'
if(-not $env:EMS_DEVELOPMENT_TASK_ID -or -not $env:EMS_TEST_PASSWORD -or -not(Test-Path -LiteralPath "$Fixture/test-fixture.json")){throw 'Managed synthetic fixture required'}
# Only this test process supplies synthetic console answers. The shipped startup
# and LocalSetup transaction execute unchanged; no unattended setup switch exists.
$global:EmsSyntheticAnswers=@('260001','Synthetic Candidate','110101199001019999','13800000001','1990-01-01','2020-01-01')
$global:EmsSyntheticIndex=0
function global:Read-Host {
 param([string]$Prompt,[switch]$AsSecureString)
 if($AsSecureString){return ConvertTo-SecureString $env:EMS_TEST_PASSWORD -AsPlainText -Force}
 if($global:EmsSyntheticIndex -ge $global:EmsSyntheticAnswers.Count){throw 'Unexpected setup prompt'}
 $value=$global:EmsSyntheticAnswers[$global:EmsSyntheticIndex];$global:EmsSyntheticIndex++;return $value
}
& "$Fixture/server-complete/start-server.ps1" -Mode $Mode -NoBrowser
exit $LASTEXITCODE
