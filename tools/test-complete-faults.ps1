param([string]$NodeExe='E:/nodejs/node.exe')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-database-faults';$fixture=Join-Path $run.path '数据库 故障恢复';$ok=$false;$failure='';$saved=@{};$shell=Join-Path $PSHOME 'pwsh.exe'
foreach($name in @('EMS_TEST_PASSWORD','EMS_TEST_COOKIE_NAME')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Run-Script([string]$File,$Arguments,[string]$Label){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-File',$File)+$Arguments) "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
function Run-Node([string]$File,$Arguments,[string]$Label){$code=Invoke-ArtifactNative $NodeExe (@($File)+$Arguments) "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
try{
 Copy-CompleteFixture $run $fixture
 $env:EMS_TEST_PASSWORD='Synthetic'+[guid]::NewGuid().ToString('N')+'7';$env:EMS_TEST_COOKIE_NAME='EMS121N_COMPLETE_SERVER'
 Run-Script "$fixture/server-complete/initialize-server.ps1" @('-DatabaseOnly') 'initialize'
 Run-Script "$root/launcher/seed-server-test.ps1" @('-Fixture',$fixture,'-RuntimeDirectory','server-complete') 'seed'
 Run-Script "$fixture/server-complete/start-server.ps1" @('-NoBrowser') 'start'
 $base='http://127.0.0.1:8483'
 Run-Node "$PSScriptRoot/test-complete-database-faults.cjs" @($base,"$($run.path)/database-fault-results.json",$fixture) 'database-faults'
 Write-ArtifactJson "$($run.path)/fault-complete-results.json" @{passed=$true;actualRollback=$true;actualBrokerInterruption=$true;ownedCompleteNamespace=$true}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 try{
  foreach($file in Get-ChildItem "$fixture/logs/complete/server" -File -Filter '*.log' -ErrorAction SilentlyContinue){[IO.File]::WriteAllText("$($run.path)/logs/application-$($file.Name)",(Protect-ArtifactText (Read-ArtifactTail $file.FullName 32768)))}
  $jobs=Join-Path $fixture 'data/complete/server/database-jobs';if(Test-Path -LiteralPath $jobs){foreach($folder in Get-ChildItem -LiteralPath $jobs -Directory){foreach($file in Get-ChildItem $folder.FullName -Filter '*.log' -File){[IO.File]::WriteAllText("$($run.path)/logs/job-$($folder.Name)-$($file.Name)",(Protect-ArtifactText (Read-ArtifactTail $file.FullName 16384)))}}}
 }catch{}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
