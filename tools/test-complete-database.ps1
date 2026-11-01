param([string]$NodeExe='E:/nodejs/node.exe',[switch]$BrowserOnly,[string]$BrowserExe='C:/Program Files/Google/Chrome/Application/chrome.exe')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-database'
$fixture=Join-Path $run.path '完整候选 数据库隔离'
$ok=$false;$failure='';$prior=$env:EMS_TEST_PASSWORD
$shell=Join-Path $PSHOME 'pwsh.exe'
try{
 Copy-CompleteFixture $run $fixture
 $env:EMS_TEST_PASSWORD='Synthetic'+[Guid]::NewGuid().ToString('N')+'7'
 foreach($step in @(@('initialize',"$fixture/server-complete/initialize-server.ps1",@('-DatabaseOnly')),@('seed',"$root/launcher/seed-server-test.ps1",@('-Fixture',$fixture,'-RuntimeDirectory','server-complete')))){
  $code=Invoke-ArtifactNative $shell (@('-NoProfile','-File',$step[1])+$step[2]) "$($run.path)/logs/$($step[0]).log";if($code -ne 0){throw "$($step[0]) failed"}
 }
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$fixture/server-complete/start-server.ps1",'-Address','127.0.0.1','-NoBrowser') "$($run.path)/logs/start.log"
 if($code -ne 0){throw 'Owned complete candidate failed to start'}
 if($BrowserOnly){if(-not(Test-Path -LiteralPath $BrowserExe)){throw 'Actual browser executable required'};$arguments=@("$PSScriptRoot/test-complete-database-browser.cjs",'http://127.0.0.1:8483',"$($run.path)/database-browser-results.json",$BrowserExe,"$($run.path)/browser-profile")}else{$arguments=@("$PSScriptRoot/test-complete-database.cjs",'http://127.0.0.1:8483',"$($run.path)/database-results.json",$fixture)}
 $code=Invoke-ArtifactNative $NodeExe $arguments "$($run.path)/logs/http.log"
 if($code -ne 0){throw 'Complete database HTTP checks failed'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 $env:EMS_TEST_PASSWORD=$prior
 try{
  $jobs=Join-Path $fixture 'data/complete/server/database-jobs'
  if(Test-Path -LiteralPath $jobs){foreach($folder in Get-ChildItem -LiteralPath $jobs -Directory){foreach($file in Get-ChildItem -LiteralPath $folder.FullName -File|Where-Object {$_.Extension -eq '.log' -or $_.Name -eq 'failure.json'}){[IO.File]::WriteAllText("$($run.path)/logs/job-$($folder.Name)-$($file.Name)",(Protect-ArtifactText (Read-ArtifactTail $file.FullName 32768)))}}}
 }catch{$failure+='; job diagnostic capture incomplete'}
 try{if(Test-Path -LiteralPath "$fixture/logs/complete/server"){foreach($file in Get-ChildItem -LiteralPath "$fixture/logs/complete/server" -Filter '*.log' -File){[IO.File]::WriteAllText("$($run.path)/logs/application-$($file.Name)",(Protect-ArtifactText (Read-ArtifactTail $file.FullName 65536)))}}}catch{$failure+='; diagnostic capture incomplete'}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
