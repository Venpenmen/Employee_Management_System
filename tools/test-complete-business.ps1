param([string]$NodeExe='E:/nodejs/node.exe')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-business';$fixture=Join-Path $run.path '业务 跨模块备份恢复';$ok=$false;$failure='';$saved=@{};$shell=Join-Path $PSHOME 'pwsh.exe'
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
 Run-Node "$root/launcher/test-server-http.cjs" @($base,"$($run.path)/business-http-results.json") 'business-http'
 Run-Script "$root/launcher/inspect-server-test.ps1" @('-Fixture',$fixture,'-OutputDir',$run.path,'-RuntimeDirectory','server-complete') 'database-relations'
 Run-Script "$root/launcher/verify-excel.ps1" @('-Workbook',"$($run.path)/salary.xlsx",'-ExpectedTsv',"$($run.path)/salary-private.tsv",'-OutputFile',"$($run.path)/excel-results.txt") 'excel-values'
 Run-Script "$root/launcher/generate-recruitment-fixture.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root,'-RuntimeDirectory','server-complete') 'workbook-fixture'
 Run-Node "$root/launcher/test-business-complete.cjs" @($base,"$($run.path)/expanded-business-results.json","$fixture/data/complete/server/recruitment-private.xlsx") 'expanded-business'
 Run-Script "$root/launcher/inspect-expanded-business.ps1" @('-Fixture',$fixture,'-RuntimeDirectory','server-complete') 'expanded-relations'
 # Re-run the affected restore/cutover contract against data produced by all
 # preceding business workflows, including manager changes and account status.
 Run-Node "$PSScriptRoot/test-complete-database.cjs" @($base,"$($run.path)/integrated-backup-results.json",$fixture) 'integrated-backup'
 Write-ArtifactJson "$($run.path)/business-complete-results.json" @{passed=$true;threeRoles=$true;businessRelations=$true;excelDecimalReadback=$true;restoredActualWorkflowData=$true;scope='owned synthetic complete runtime; no original data or service touched'}
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
