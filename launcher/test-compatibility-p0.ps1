param([switch]$ReproduceOriginal,[switch]$Historical)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$run=New-ArtifactRun $root 'database-compatibility';$fixture=Join-Path $run.path '独立兼容验收';$ok=$false;$failure='';$oldBundle=$env:EMS_DEMO_BUNDLE;$oldUrl=$env:EMS_IT_URL;$oldPassword=$env:EMS_IT_PASSWORD
$shell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"
$oldReference=$env:EMS_REPRO_REFERENCE_ZIP
try{
 [IO.Directory]::CreateDirectory($fixture)|Out-Null
 foreach($name in @('runtime','app','bootstrap','server','launcher')){Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $fixture $name) -Recurse}
 Write-ArtifactJson "$fixture/test-fixture.json" @{run=$run.id;version='1.2.1'};Register-ArtifactInstance $run $fixture
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture/server/initialize-server.ps1",'-DatabaseOnly') "$($run.path)/logs/initialize.log";if($code -ne 0){throw 'Independent initialization failed'}
 . "$fixture/server/runtime-common.ps1"
 $outside=Join-Path $run.path ([Guid]::NewGuid().ToString());[IO.Directory]::CreateDirectory($outside)|Out-Null
 Write-ArtifactJson "$outside/status.json" @{state='SENTINEL';message='must remain unchanged'};$sentinel=(Get-FileHash "$outside/status.json").Hash
 $refused=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture/server/database-job.ps1",'-Mode','server','-Action','restore','-LocalTask','-Job',$outside) "$($run.path)/logs/outside-job-refused.log"
 if($refused -ne 1 -or (Get-FileHash "$outside/status.json").Hash -ne $sentinel -or (Test-Path "$outside/failure.json")){throw 'Out-of-scope job wrote to sentinel'}
 $state=Read-EmsState;$secret=Read-EmsSecrets
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($state.dbPort)/$script:DbName`?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
 $env:EMS_IT_PASSWORD=$secret.root;$env:EMS_DEMO_BUNDLE=Join-Path (Split-Path $root) 'employee-management-system_v1.2.0/dist/EMS_v1.2.0_DemoData.zip'
 $cp="$root/backend/target/test-classes;$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim()
 $arguments=@('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.LegacyDemoImportIT',(Join-Path $script:DataDir 'legacy-demo-test'))
 if($ReproduceOriginal){$arguments+='--expect-original-failure';if(-not $env:EMS_REPRO_REFERENCE_ZIP){$env:EMS_REPRO_REFERENCE_ZIP=Join-Path $root 'dist/rc2/EMS员工管理系统_v1.2.1-next-rc2_服务器版_Windows_x64.zip'}}
 if($Historical){[IO.Directory]::CreateDirectory((Join-Path $script:DataDir 'historical-checks'))|Out-Null;$arguments=@('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.UpgradeDatabaseIT',(Join-Path $script:DataDir 'historical-checks'))}
 $code=Invoke-ArtifactNative $script:JavaExe $arguments "$($run.path)/logs/demo-compatibility.log";if($code -ne 0){throw 'Legacy demo integration failed'}
 if(-not $Historical -and -not $ReproduceOriginal){
  $failedId=[Guid]::NewGuid().ToString();$failedJob=Join-Path $script:DataDir ('database-jobs/'+$failedId);[IO.Directory]::CreateDirectory($failedJob)|Out-Null
  Write-ArtifactJson "$failedJob/status.json" @{id=$failedId;action='import';state='RUNNING';message='synthetic failure test'}
  Copy-Item -LiteralPath (Join-Path $script:DataDir 'legacy-demo-test/input.emsbk') -Destination "$failedJob/input.emsbk"
  $priorBackup=$env:EMS_BACKUP_PASSWORD
  try{$env:EMS_BACKUP_PASSWORD='WrongSyntheticBackup'+[Guid]::NewGuid().ToString('N');$failed=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture/server/database-job.ps1",'-Mode','server','-Action','restore','-LocalTask','-Job',$failedJob) "$($run.path)/logs/safe-child-diagnostic.log"}finally{$env:EMS_BACKUP_PASSWORD=$priorBackup}
  $diagnostic=Get-Content "$failedJob/failure.json" -Raw -Encoding UTF8|ConvertFrom-Json
  if($failed -ne 1 -or $diagnostic.code -ne 'BACKUP_INVALID' -or $diagnostic.phase -ne 'decrypt' -or $diagnostic.activeDatabase -ne 'UNCHANGED'){throw 'Valid owned failure lost its diagnostic'}
  Write-ArtifactJson "$($run.path)/child-diagnostics-results.json" @{passed=$true;outsidePathRejected=$true;sentinelUnchanged=$true;ownedFailureCode=$diagnostic.code;phase=$diagnostic.phase;activeDatabase=$diagnostic.activeDatabase}
 }
 $mapping=Join-Path $script:DataDir 'legacy-demo-test/field-mapping-results.json';if(Test-Path -LiteralPath $mapping){Copy-Item -LiteralPath $mapping -Destination (Join-Path $run.path 'field-mapping-results.json')}
 Write-ArtifactJson "$($run.path)/compatibility-results.json" @{passed=$true;originalFailureReproduction=[bool]$ReproduceOriginal;scope='preserved synthetic demo; independent schema; no installed database connection'};$ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$env:EMS_REPRO_REFERENCE_ZIP=$oldReference;$env:EMS_DEMO_BUNDLE=$oldBundle;$env:EMS_IT_URL=$oldUrl;$env:EMS_IT_PASSWORD=$oldPassword;$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
