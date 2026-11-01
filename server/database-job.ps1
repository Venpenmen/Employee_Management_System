param([ValidateSet('server','portable','dev')][string]$Mode='server',[ValidateSet('export','restore','verify-admin','commit')][string]$Action,[Parameter(Mandatory=$true)][string]$Job,[switch]$LocalTask)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$lock=$null;$oldSchema=$null;$state=$null;$switched=$false;$jobOwned=$false;$script:TaskDiagnostic=$null
function Status([string]$Value,[string]$Message){
 $p=Join-Path $Job 'status.json';$record=Get-Content -LiteralPath $p -Raw -Encoding UTF8|ConvertFrom-Json
 $record.state=$Value;$record.message=$Message;if($script:TaskDiagnostic){$record|Add-Member -NotePropertyName diagnostic -NotePropertyValue $script:TaskDiagnostic -Force};Write-Utf8 ($p+'.tmp') ($record|ConvertTo-Json -Depth 8);Move-Item -LiteralPath ($p+'.tmp') -Destination $p -Force
}
function Start-OwnApplication {
 param($State)
 $env:EMS_SWITCH_TASK=[IO.Path]::GetFileName($Job)
 $shell="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\start-server.ps1",'-Mode',$Mode,'-Address',[string]$State.address,'-Port',[string]$State.httpPort,'-NoBrowser') (Join-Path $Job ('restart-'+[Guid]::NewGuid().ToString('N')+'.log'))
 if($code -ne 0){throw 'Owned application restart failed'}
}
try{
 $Job=Assert-ArtifactPath (Join-Path $script:DataDir 'database-jobs') $Job
 if([IO.Path]::GetFileName($Job) -notmatch '^[a-f0-9-]{36}$'){throw 'Invalid job id'}
 $lock=Enter-EmsLock;$jobOwned=$true;$state=Read-EmsState
 if(-not $state -or -not(Get-OwnedProcess $state.database)){throw 'Owned database is not running'}
 Mount-EmsNativeRoot $state
 if($Action -eq 'commit' -and $LocalTask){throw 'Local bootstrap cannot confirm a web import'}
 if($Action -ne 'commit'){Invoke-EmsMaintenance $Action $state $Job -WebAuthorized:(-not $LocalTask);exit 0}
 if(-not(Get-OwnedProcess $state.app)){throw 'Controlled switch requires a launcher owned process'}
 $result=Get-Content -LiteralPath (Join-Path $Job 'result.json') -Raw -Encoding UTF8|ConvertFrom-Json
 if($result.target -notmatch ('^ems_next_'+$Mode+'_v121_g[a-f0-9]{12}$') -or $result.target -eq $script:DbName){throw 'Invalid isolated target'}
 $oldSchema=$script:DbName
 Write-Utf8 (Join-Path $Job 'switch-journal.json') (@{old=$oldSchema;target=$result.target;phase='PREPARED';mode=$Mode;buildId='v1.2.1-next-rc2';ownerId=$PID;ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString();ownerExe=(Get-Process -Id $PID).Path}|ConvertTo-Json)
 Write-Utf8 "$script:DataDir\maintenance.flag" ([IO.Path]::GetFileName($Job))
 $ready=$false
 for($i=0;$i -lt 60;$i++){if((Test-Path -LiteralPath "$script:DataDir\quiesced") -and [IO.File]::ReadAllText("$script:DataDir\quiesced") -eq [IO.Path]::GetFileName($Job)){$ready=$true;break};Start-Sleep -Seconds 1}
 if(-not $ready){throw 'Request drain timed out'}
 Invoke-EmsMaintenance 'verify-current' $state $Job -WebAuthorized
 Invoke-EmsMaintenance 'verify-current' $state $Job
 $backup=Join-Path $script:DataDir ('backups/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N'))
 New-Item -ItemType Directory -Path $backup -Force|Out-Null
 $pass=New-EmsSecret
 $wrapped=ConvertFrom-SecureString (ConvertTo-SecureString $pass -AsPlainText -Force)
 Write-Utf8 (Join-Path $backup 'recovery.key.dpapi') $wrapped
 $prior=$env:EMS_BACKUP_PASSWORD
 try{
  $env:EMS_BACKUP_PASSWORD=$pass;Invoke-EmsMaintenance 'export' $state $backup -WebAuthorized
  $verification=Join-Path $backup 'verification';New-Item -ItemType Directory -Path $verification|Out-Null
  Copy-Item -LiteralPath (Join-Path $backup 'backup.emsbk') -Destination (Join-Path $verification 'input.emsbk')
  Invoke-EmsMaintenance 'restore' $state $verification -WebAuthorized
  $verified=Get-Content -LiteralPath (Join-Path $verification 'result.json') -Raw -Encoding UTF8|ConvertFrom-Json
  if($verified.target -notmatch ('^ems_next_'+$Mode+'_v121_g[a-f0-9]{12}$') -or $verified.target -in @($oldSchema,$result.target)){throw 'Unsafe verification target'}
  $verifyClient=Write-ClientConfig $state
  Invoke-EmsTool $script:MysqlExe @("--defaults-file=$verifyClient",'-e',("DROP DATABASE "+$verified.target))|Out-Null
  Write-Utf8 (Join-Path $backup 'verification.json') (@{passed=$true;tables=@($verified.tables.PSObject.Properties).Count;restoredBeforeSwitch=$true}|ConvertTo-Json)
  Remove-Item -LiteralPath (Join-Path $verification 'input.emsbk'),(Join-Path $verification 'result.json') -Force
  [IO.Directory]::Delete($verification)
 }finally{$env:EMS_BACKUP_PASSWORD=$prior;$pass=$null}
 Stop-EmsApplication $state;Save-EmsState $state
 $cnf=Write-ClientConfig $state
 Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-e',"GRANT SELECT,INSERT,UPDATE,DELETE ON $($result.target).* TO 'ems_app'@'127.0.0.1';")|Out-Null
 Write-Utf8 "$script:DataDir\active-schema.tmp" $result.target
 Move-Item -LiteralPath "$script:DataDir\active-schema.tmp" -Destination "$script:DataDir\active-schema" -Force;$switched=$true
 Write-Utf8 (Join-Path $Job 'switch-journal.json') (@{old=$oldSchema;target=$result.target;phase='POINTER_SWITCHED';mode=$Mode;buildId='v1.2.1-next-rc2';ownerId=$PID;ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString();ownerExe=(Get-Process -Id $PID).Path}|ConvertTo-Json)
 if((Test-Path -LiteralPath "$script:EmsRoot/test-fixture.json") -and (Test-Path -LiteralPath "$script:DataDir/inject-switch-failure")){throw 'Injected isolated switch failure'}
 if((Test-Path -LiteralPath "$script:EmsRoot/test-fixture.json") -and (Test-Path -LiteralPath "$script:DataDir/pause-switch-test")){
  Write-Utf8 (Join-Path $Job 'broker-paused') ([string]$PID)
  while(Test-Path -LiteralPath "$script:DataDir/pause-switch-test"){Start-Sleep -Seconds 1}
 }
 $lock.Dispose();$lock=$null
 Start-OwnApplication $state
 $null=& {
  $script:DbName=$result.target
  $current=Read-EmsState;Mount-EmsNativeRoot $current
  $cnf=Write-ClientConfig $current
  Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",$script:DbName,'-e',"INSERT INTO operation_log(operator_id,operation_type,target_type,summary) VALUES(NULL,'DATABASE_IMPORT','DATABASE','Isolated restore committed; old sessions invalidated');")|Out-Null
 }
 Status 'SUCCEEDED' '导入成功，请用备份中的账号重新登录'
 Write-Utf8 (Join-Path $Job 'switch-journal.json') (@{old=$oldSchema;target=$result.target;phase='COMMITTED';mode=$Mode;buildId='v1.2.1-next-rc2';ownerId=$PID;ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString();ownerExe=(Get-Process -Id $PID).Path}|ConvertTo-Json)
 exit 0
}catch{
 if(-not $jobOwned){Write-Host 'Database task refused before path/lock ownership; no task files changed';exit 1}
 $reason=$_.Exception.GetType().Name
 # Java writes a catalogued, value-free diagnostic before exiting. Never echo the
 # wrapper exception message: it may contain stderr, SQL, credentials or local paths.
 $diagnosticPath=Join-Path $Job 'failure.json'
 $diagnostic=$null
 if(Test-Path -LiteralPath $diagnosticPath){try{$diagnostic=Get-Content -LiteralPath $diagnosticPath -Raw -Encoding UTF8|ConvertFrom-Json}catch{}}
 if(-not $diagnostic){
  $diagnostic=[pscustomobject]@{errorId=[IO.Path]::GetFileName($Job);code='PROCESS_FAILED';phase='launcher';message='维护子进程失败，请保留任务编号检查';sqlState='';vendorCode=0;activeDatabase='UNCHANGED';recommendation='保留任务与隔离目标；勿清空原库或执行repair'}
  try{Write-Utf8 $diagnosticPath ($diagnostic|ConvertTo-Json -Depth 5)}catch{}
 }
 $script:TaskDiagnostic=$diagnostic
 if($switched){$diagnostic.activeDatabase='UNKNOWN_REQUIRES_VERIFICATION'}
 if($switched -and $oldSchema){
  try{
   if($lock){$lock.Dispose();$lock=$null}
   & "$PSScriptRoot\stop-server.ps1" -Mode $Mode|Out-Null
   Write-Utf8 "$script:DataDir\active-schema" $oldSchema
   Start-OwnApplication $state
   $diagnostic.activeDatabase='ROLLED_BACK';Status 'ROLLED_BACK' '切换未完成，已恢复原库，请重新登录'
   $journalPath=Join-Path $Job 'switch-journal.json'
   $journal=Get-Content -LiteralPath $journalPath -Raw -Encoding UTF8|ConvertFrom-Json
   $journal.phase='ROLLED_BACK'
   Write-Utf8 ($journalPath+'.tmp') ($journal|ConvertTo-Json -Depth 6)
   Move-Item -LiteralPath ($journalPath+'.tmp') -Destination $journalPath -Force
  }catch{Status 'RECOVERY_REQUIRED' '原库已保留，自动恢复未完成，请检查本机切换日志'}
 }else{try{Status 'FAILED' ($diagnostic.message+'；原库未切换；错误编号：'+[IO.Path]::GetFileName($Job))}catch{}}
 try{Write-Utf8 $diagnosticPath ($diagnostic|ConvertTo-Json -Depth 5)}catch{}
 Write-Host ('Database task diagnostic: '+($diagnostic|ConvertTo-Json -Depth 5 -Compress))
 Write-Host ("Database task failed ["+[IO.Path]::GetFileName($Job)+"] "+$reason)
 exit 1
}finally{
 if($jobOwned){
 foreach($name in @('maintenance.flag','quiesced')){
  $marker=Join-Path $script:DataDir $name
  if((Test-Path -LiteralPath $marker) -and [IO.File]::ReadAllText($marker).Trim() -eq [IO.Path]::GetFileName($Job)){
   Remove-Item -LiteralPath $marker -ErrorAction SilentlyContinue
  }
 }
 }
 Clear-ClientConfigs;if($lock){$lock.Dispose()}
}
