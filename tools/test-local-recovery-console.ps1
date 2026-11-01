param([Parameter(Mandatory=$true)][string]$Fixture,[ValidateSet('Apply','Cancel')][string]$Case='Apply')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot
. "$project/launcher/artifact-common.ps1"
$fixturePath=[IO.Path]::GetFullPath($Fixture);$runPath=Split-Path $fixturePath
$run=Read-ArtifactContext $project (Join-Path $runPath 'run.json')
$null=Assert-ArtifactPath $run.path $fixturePath
$owner=Get-Process -Id $run.manifest.ownerId -ErrorAction Stop
if($run.manifest.status -ne 'running' -or $run.manifest.ownerTaskId -ne $env:EMS_DEVELOPMENT_TASK_ID -or $owner.StartTime.ToUniversalTime().Ticks.ToString() -ne $run.manifest.ownerTicks -or $owner.Path -ne $run.manifest.ownerExecutable -or -not(Test-Path -LiteralPath "$fixturePath/test-fixture.json")){throw 'Only a live task-owned synthetic fixture may use this console adapter'}
function Read-Host {
 param([string]$Prompt,[switch]$AsSecureString)
 if($AsSecureString){$value=if($Prompt -like '*独立复核*'){$env:EMS_TEST_REVIEWER_PASSWORD}else{$env:EMS_TEST_PASSWORD};return ConvertTo-SecureString $value -AsPlainText -Force}
 if($Prompt -like '*六位工号*'){return '260003'}
 if($Prompt -match 'APPLY ([a-f0-9-]{36})'){if($Case -eq 'Cancel'){return 'CANCEL'};return ('APPLY '+$Matches[1])}
 throw 'Unexpected console prompt; refuse rather than fabricate an answer'
}
& "$fixturePath/server-complete/recover-secondary.ps1" -TargetEmployeeNo '260001' -ApprovalReference 'SYNTHETIC-RECOVERY-APPROVAL'
exit $LASTEXITCODE
