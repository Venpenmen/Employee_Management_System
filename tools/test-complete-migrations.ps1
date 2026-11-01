param()
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-migration-regression';$ok=$false;$failure='';$shell=Join-Path $PSHOME 'pwsh.exe'
try{
 foreach($mode in @('server','portable')){
  $fixture=Join-Path $run.path ("migration "+$mode)
  Copy-CompleteFixture $run $fixture
  $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$fixture/server-complete/initialize-server.ps1",'-DatabaseOnly','-Mode',$mode) "$($run.path)/logs/$mode-initialize.log";if($code -ne 0){throw "$mode initialization failed"}
  $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$root/launcher/test-rc2-database.ps1",'-Fixture',$fixture,'-ProjectRoot',$root,'-Mode',$mode,'-RuntimeDirectory','server-complete') "$($run.path)/logs/$mode-migration.log";if($code -ne 0){throw "$mode migration regression failed"}
  $log=[IO.File]::ReadAllText("$($run.path)/logs/$mode-migration.log")
  if($log -notmatch 'rc2 migration checks passed: (\d+)'){throw 'Missing actual SQL migration success sentinel'}
  Write-ArtifactJson "$($run.path)/$mode-migration-results.json" @{passed=$true;count=[int]$Matches[1];mode=$mode;historicalExportersHashVerified=$true;launcherPreflight=$true;incompleteMigrationRefused=$true}
 }
 Write-ArtifactJson "$($run.path)/complete-migration-results.json" @{passed=$true;server=$true;portable=$true;scope='owned synthetic isolated schemas; actual historical exporter binaries; no active data copied or connected'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
