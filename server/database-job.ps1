param([ValidateSet('server','portable','dev')][string]$Mode='server',[ValidateSet('export','restore','verify-admin','commit')][string]$Action,[Parameter(Mandatory=$true)][string]$Job,[switch]$LocalTask)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$lock=$null;$oldSchema=$null;$state=$null;$switched=$false
function Status([string]$Value,[string]$Message){
 $p=Join-Path $Job 'status.json';$record=Get-Content -LiteralPath $p -Raw -Encoding UTF8|ConvertFrom-Json
 $record.state=$Value;$record.message=$Message;Write-Utf8 ($p+'.tmp') ($record|ConvertTo-Json -Depth 8);Move-Item -LiteralPath ($p+'.tmp') -Destination $p -Force
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
 $lock=Enter-EmsLock;$state=Read-EmsState
 if(-not $state -or -not(Get-OwnedProcess $state.database)){throw 'Owned database is not running'}
 Mount-EmsNativeRoot $state
 if($Action -eq 'commit' -and $LocalTask){throw 'Local bootstrap cannot confirm a web import'}
 if($Action -ne 'commit'){Invoke-EmsMaintenance $Action $state $Job -WebAuthorized:(-not $LocalTask);exit 0}
 if(-not(Get-OwnedProcess $state.app)){throw 'Controlled switch requires a launcher owned process'}
 $result=Get-Content -LiteralPath (Join-Path $Job 'result.json') -Raw -Encoding UTF8|ConvertFrom-Json
 if($result.target -notmatch ('^ems_up_'+$Mode+'_v120_g[a-f0-9]{12}$') -or $result.target -eq $script:DbName){throw 'Invalid isolated target'}
 $oldSchema=$script:DbName
 Write-Utf8 (Join-Path $Job 'switch-journal.json') (@{old=$oldSchema;target=$result.target;phase='PREPARED';mode=$Mode;buildId='v1.2.0-rc1';ownerId=$PID;ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString();ownerExe=(Get-Process -Id $PID).Path}|ConvertTo-Json)
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
  if($verified.target -notmatch ('^ems_up_'+$Mode+'_v120_g[a-f0-9]{12}$') -or $verified.target -in @($oldSchema,$result.target)){throw 'Unsafe verification target'}
  $verifyClient=Write-ClientConfig $state
  Invoke-EmsTool $script:MysqlExe @("--defaults-file=$verifyClient",'-e',("DROP DATABASE "+$verified.target))|Out-Null
  Write-Utf8 (Join-Path $backup 'verification.json') (@{passed=$true;tables=17;restoredBeforeSwitch=$true}|ConvertTo-Json)
  Remove-Item -LiteralPath (Join-Path $verification 'input.emsbk'),(Join-Path $verification 'result.json') -Force
  [IO.Directory]::Delete($verification)
 }finally{$env:EMS_BACKUP_PASSWORD=$prior;$pass=$null}
 Stop-EmsApplication $state;Save-EmsState $state
 $cnf=Write-ClientConfig $state
 Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-e',"GRANT SELECT,INSERT,UPDATE,DELETE ON $($result.target).* TO 'ems_app'@'127.0.0.1';")|Out-Null
 Write-Utf8 "$script:DataDir\active-schema.tmp" $result.target
 Move-Item -LiteralPath "$script:DataDir\active-schema.tmp" -Destination "$script:DataDir\active-schema" -Force;$switched=$true
 Write-Utf8 (Join-Path $Job 'switch-journal.json') (@{old=$oldSchema;target=$result.target;phase='POINTER_SWITCHED';mode=$Mode;buildId='v1.2.0-rc1';ownerId=$PID;ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString();ownerExe=(Get-Process -Id $PID).Path}|ConvertTo-Json)
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
 Write-Utf8 (Join-Path $Job 'switch-journal.json') (@{old=$oldSchema;target=$result.target;phase='COMMITTED';mode=$Mode;buildId='v1.2.0-rc1';ownerId=$PID;ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString();ownerExe=(Get-Process -Id $PID).Path}|ConvertTo-Json)
 exit 0
}catch{
 $reason=$_.Exception.GetType().Name
 if($switched -and $oldSchema){
  try{
   if($lock){$lock.Dispose();$lock=$null}
   & "$PSScriptRoot\stop-server.ps1" -Mode $Mode|Out-Null
   Write-Utf8 "$script:DataDir\active-schema" $oldSchema
   Start-OwnApplication $state
   Status 'ROLLED_BACK' '切换未完成，已恢复原库，请重新登录'
   $journalPath=Join-Path $Job 'switch-journal.json'
   $journal=Get-Content -LiteralPath $journalPath -Raw -Encoding UTF8|ConvertFrom-Json
   $journal.phase='ROLLED_BACK'
   Write-Utf8 ($journalPath+'.tmp') ($journal|ConvertTo-Json -Depth 6)
   Move-Item -LiteralPath ($journalPath+'.tmp') -Destination $journalPath -Force
  }catch{Status 'RECOVERY_REQUIRED' '原库已保留，自动恢复未完成，请检查本机切换日志'}
 }else{try{Status 'FAILED' '操作未完成，原库未切换'}catch{}}
 Write-Error ("Database task failed ["+[IO.Path]::GetFileName($Job)+"] "+$reason) -ErrorAction Continue
 exit 1
}finally{
 foreach($name in @('maintenance.flag','quiesced')){
  $marker=Join-Path $script:DataDir $name
  if((Test-Path -LiteralPath $marker) -and [IO.File]::ReadAllText($marker).Trim() -eq [IO.Path]::GetFileName($Job)){
   Remove-Item -LiteralPath $marker -ErrorAction SilentlyContinue
  }
 }
 Clear-ClientConfigs;if($lock){$lock.Dispose()}
}
