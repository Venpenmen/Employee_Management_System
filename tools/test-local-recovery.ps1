param([ValidateSet('Database','All')][string]$Layer='All')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'local-secondary-recovery'
$fixture=Join-Path $run.path '本机双人恢复 独立实例'
$ok=$false;$failure='';$saved=@{}
foreach($name in @('EMS_IT_URL','EMS_IT_PASSWORD','EMS_IT_ROOT','EMS_TEST_PASSWORD','EMS_TEST_REVIEWER_PASSWORD')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$shell=Join-Path $PSHOME 'pwsh.exe'
try{
 Copy-CompleteFixture $run $fixture
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$fixture/server-complete/initialize-server.ps1",'-DatabaseOnly') "$($run.path)/logs/initialize.log";if($code -ne 0){throw 'Recovery fixture initialization failed'}
 . "$fixture/server-complete/runtime-common.ps1"
 $state=Read-EmsState;Mount-EmsNativeRoot $state;$secret=Read-EmsSecrets
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($state.dbPort)/$script:DbName`?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=60000"
 $env:EMS_IT_PASSWORD=$secret.root;$env:EMS_IT_ROOT=$fixture;$env:EMS_TEST_PASSWORD='Target'+[guid]::NewGuid().ToString('N')+'7';$env:EMS_TEST_REVIEWER_PASSWORD='Reviewer'+[guid]::NewGuid().ToString('N')+'9'
 $cp="$root/backend/target/test-classes;$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim()
 $work=Join-Path $run.path 'database-checks'
 $code=Invoke-ArtifactNative $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.LocalSecondaryRecoveryIT',$work) "$($run.path)/logs/database.log";if($code -ne 0){throw 'Local recovery SQL matrix failed'}
 Copy-Item -LiteralPath "$work/local-recovery-database-results.json" -Destination "$($run.path)/local-recovery-database-results.json"
 if($Layer -eq 'All'){
  $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$PSScriptRoot/test-local-recovery-console.ps1",'-Fixture',$fixture,'-Case','Cancel') "$($run.path)/logs/console-cancel.log";if($code -eq 0){throw 'Synthetic cancellation was accepted as a successful recovery'}
  $config=Write-ClientConfig $state
  $count=[int](Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config",'-N',$script:DbName,'-e','SELECT COUNT(*) FROM secondary_credential WHERE employee_id=1'))
  if($count -ne 1){throw 'Cancelled console flow changed the credential'}
  $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$PSScriptRoot/test-local-recovery-console.ps1",'-Fixture',$fixture) "$($run.path)/logs/console-apply.log";if($code -ne 0){throw 'Actual console workflow failed'}
  $verify=Join-Path $run.path 'console-checks'
  $code=Invoke-ArtifactNative $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.LocalSecondaryRecoveryIT',$verify,'VerifyConsole',"$work/after.zip") "$($run.path)/logs/console-readback.log";if($code -ne 0){throw 'Console recovery invariants failed'}
  Copy-Item -LiteralPath "$verify/local-recovery-console-results.json" -Destination "$($run.path)/local-recovery-console-results.json"
 }
 Write-ArtifactJson "$($run.path)/recovery-layer-results.json" @{passed=$true;layer=$Layer;consoleExecuted=($Layer -eq 'All');syntheticIndependentReview=$true}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 try{Clear-ClientConfigs}catch{}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
