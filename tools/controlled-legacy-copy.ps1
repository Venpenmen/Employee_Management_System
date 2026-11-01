param(
 [Parameter(Mandatory=$true)][ValidateSet('Create','Start','Stop','Status','ExportReturn')][string]$Action,
 [string]$CopyId,[string]$BaseFile,[string]$ExtensionFile,[Security.SecureString]$BackupPassword,
 [string]$LegacyZip,[ValidateRange(1024,65535)][int]$Port=8485
)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$root/tools/compatibility-runtime.ps1"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$run=$null;$fixture=$null;$failed=$false;$saved=@{}
foreach($key in @('EMS_DEVELOPMENT_TASK_ID','EMS_CONTROLLED_ROOT','EMS_COMPAT_WORK','EMS_IT_URL','EMS_IT_PASSWORD','EMS_BACKUP_PASSWORD','SERVER_SERVLET_SESSION_COOKIE_NAME')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$shell=if(Test-Path -LiteralPath (Join-Path $PSHOME 'pwsh.exe')){Join-Path $PSHOME 'pwsh.exe'}else{"$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"}
function Open-PrivatePassword {
 $secure=ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path $run.path 'private/archive-password.dpapi')))
 $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
 try{return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$secure.Dispose()}
}
function Invoke-CopyWorker([string]$Command){
 . "$fixture/server/runtime-common.ps1"
 $state=Read-EmsState;Mount-EmsNativeRoot $state;$secret=Read-EmsSecrets
 $env:EMS_CONTROLLED_ROOT=$fixture;$env:EMS_COMPAT_WORK=Join-Path $run.path 'private'
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($state.dbPort)/$script:DbName`?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=60000"
 $env:EMS_IT_PASSWORD=$secret.root;$env:EMS_BACKUP_PASSWORD=Open-PrivatePassword
 $jar=Resolve-CompatibilityWorkerJar $root
 if($jar){$arguments=@('-Dloader.main=com.example.ems.upgrade.LegacyCopyMain','-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher',$Command)}
 elseif((Test-Path -LiteralPath "$root/backend/target/classes/com/example/ems/upgrade/LegacyCopyMain.class") -and (Test-Path -LiteralPath "$root/backend/target/dev-classpath.txt")){$cp="$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim();$arguments=@('-cp',$cp,'com.example.ems.upgrade.LegacyCopyMain',$Command)}
 else{throw 'Compatibility worker missing; use the complete candidate or compile this source project offline'}
 try{$code=Invoke-ArtifactNative "$root/runtime/java/bin/java.exe" $arguments (Join-Path $run.path ('logs/worker-'+[guid]::NewGuid().ToString('N')+'.log'));if($code -ne 0){throw 'Controlled operation rejected; diagnostic retained in this copy logs'}}
 finally{$env:EMS_BACKUP_PASSWORD=$null;$env:EMS_IT_PASSWORD=$null;Clear-ClientConfigs}
}
function Stop-Copy {
 if(Test-Path -LiteralPath "$fixture/data/server/instance-upgrade.json"){
  . "$fixture/server/runtime-common.ps1"
  $state=Read-EmsState;if($state){Mount-EmsNativeRoot $state;Stop-EmsApplication $state;Stop-EmsDatabase $state;Dismount-EmsNativeRoot $state;Save-EmsState $state};Clear-ClientConfigs
 }
}
function Initialize-CopyDatabase {
 $alreadyRunning=& { . "$fixture/server/runtime-common.ps1";$state=Read-EmsState;return [bool]($state -and (Get-OwnedProcess $state.database)) }
 if($alreadyRunning){return}
 $arguments=@('-NoProfile');if([IO.Path]::GetFileName($shell) -eq 'powershell.exe'){$arguments+=@('-ExecutionPolicy','Bypass')}
 $arguments+=@('-File',"$fixture/server/initialize-server.ps1",'-DatabaseOnly')
 $code=Invoke-ArtifactNative $shell $arguments (Join-Path $run.path ('logs/initialize-'+[guid]::NewGuid().ToString('N')+'.log'));if($code -ne 0){throw 'Controlled database initialization failed; existing installations untouched'}
}
try{
 if($Action -eq 'Create'){
  if($CopyId){throw 'Create always allocates a fresh copy; never specify an existing CopyId'}
  if(-not $BaseFile -or -not $ExtensionFile){throw 'Provide base-v120.emsbk and extensions.emsbk'}
  foreach($file in @($BaseFile,$ExtensionFile)){if(-not(Test-Path -LiteralPath $file -PathType Leaf)){throw 'Input encrypted file missing'}}
  if(-not $LegacyZip){$LegacyZip=Join-Path $root 'compatibility/EMS_v1.2.0_Windows_Portable.zip'}
  if((Get-FileHash -LiteralPath $LegacyZip -Algorithm SHA256).Hash -ne '1FC499790A29B836442DB0451CB74C277B45573B87F084AD7FCFC272B0047203'){throw 'Only the registered immutable v1.2.0 runtime archive is supported'}
  if(([IO.DriveInfo]::new([IO.Path]::GetPathRoot($root))).AvailableFreeSpace -lt 1500MB){throw 'Insufficient space; no copy created'}
  if(-not $BackupPassword){$BackupPassword=Read-Host '输入配对备份口令（不会显示）' -AsSecureString}
  if(-not $env:EMS_DEVELOPMENT_TASK_ID){$env:EMS_DEVELOPMENT_TASK_ID=[guid]::NewGuid().ToString()}
  $run=New-ArtifactRun $root 'controlled-legacy-user' -KeepArtifacts;$fixture=Join-Path $run.path 'controlled-copy'
  Write-Output ('已分配独立副本，CopyId='+$run.id+'；若准备失败，请保留此编号用于状态检查')
  $private=Join-Path $run.path 'private';[IO.Directory]::CreateDirectory($private)|Out-Null
  $acl=New-Object Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false)
  foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User,(New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))){$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))}
  if(([IO.DirectoryInfo]$private).PSObject.Methods.Name -contains 'SetAccessControl'){([IO.DirectoryInfo]$private).SetAccessControl($acl)}else{[IO.FileSystemAclExtensions]::SetAccessControl(([IO.DirectoryInfo]$private),$acl)}
  [IO.File]::WriteAllText((Join-Path $private 'archive-password.dpapi'),(ConvertFrom-SecureString $BackupPassword))
  Copy-Item -LiteralPath $BaseFile -Destination (Join-Path $private 'base-v120.emsbk');Copy-Item -LiteralPath $ExtensionFile -Destination (Join-Path $private 'extensions.emsbk')
  [IO.Compression.ZipFile]::ExtractToDirectory($LegacyZip,$fixture);$null=@(Get-ArtifactFiles $fixture -Strict);Register-ArtifactInstance $run $fixture
  Initialize-CopyDatabase;Invoke-CopyWorker 'install';Stop-Copy
  Write-ArtifactJson (Join-Path $run.path 'copy-ready.json') @{schema=1;copyId=$run.id;port=$Port;cookie=('EMS120_P1_'+[guid]::NewGuid().ToString('N'));legacyArchiveSha256='1fc499790a29b836442db0451cb74c277b45573b87f084ad7fcfc272b0047203'}
  Write-Output ('受控副本已创建，CopyId='+$run.id)
 }else{
  if($CopyId -notmatch '^\d{8}-\d{6}-[a-f0-9]{12}$'){throw 'Invalid CopyId'}
  $manifest=Join-Path $root ('.release-work/runs/'+$CopyId+'/run.json');$context=Read-ArtifactContext $root $manifest
  if($context.manifest.kind -ne 'controlled-legacy-user' -or -not $context.manifest.keep){throw 'Not a user controlled copy; refusing to operate'}
  $env:EMS_DEVELOPMENT_TASK_ID=$context.manifest.ownerTaskId
  # Status must not claim ownership or rewrite a retained copy's manifest.
  if($Action -eq 'Status'){$run=$context}else{$run=Recover-ArtifactRun $root $manifest;$run.manifest.status='running';Write-ArtifactJson $manifest $run.manifest}
  $fixture=Assert-ArtifactPath $run.path (Join-Path $run.path 'controlled-copy')
  if($Action -eq 'Stop'){Stop-Copy;Write-Output '本受控副本已正常停止'}
  elseif($Action -eq 'Status'){
   & { . "$fixture/server/runtime-common.ps1";$state=Read-EmsState;if(-not $state){Write-Output '本副本尚未初始化或实例状态缺失；应用运行=False；数据库运行=False';return};Write-Output ('本副本应用运行='+[bool](Get-OwnedProcess $state.app)+'；数据库运行='+[bool](Get-OwnedProcess $state.database)) }
  }else{
   $ready=Get-Content -LiteralPath (Join-Path $run.path 'copy-ready.json') -Raw -Encoding UTF8|ConvertFrom-Json
   if($ready.copyId -ne $CopyId){throw 'Copy preparation did not finish; only Stop or Status is permitted'}
   Initialize-CopyDatabase;Invoke-CopyWorker 'check'
   if($Action -eq 'ExportReturn'){Invoke-CopyWorker 'return';Write-Output ('回程文件已保存到本副本 private/return-*，CopyId='+$CopyId)}
   else{
    $env:SERVER_SERVLET_SESSION_COOKIE_NAME=$ready.cookie
    $arguments=@('-NoProfile');if([IO.Path]::GetFileName($shell) -eq 'powershell.exe'){$arguments+=@('-ExecutionPolicy','Bypass')}
    $arguments+=@('-File',"$fixture/server/start-server.ps1",'-Address','127.0.0.1','-Port',[string]$ready.port,'-NoBrowser')
    $code=Invoke-ArtifactNative $shell $arguments (Join-Path $run.path ('logs/start-'+[guid]::NewGuid().ToString('N')+'.log'));if($code -ne 0){throw 'Controlled copy start failed; inspect retained logs'}
    Write-Output ('受控旧版已启动：http://127.0.0.1:'+$ready.port)
   }
  }
 }
}catch{$failed=$true;if($run -and $Action -eq 'Create'){try{Stop-Copy}catch{Write-Warning 'Owned copy could not fully stop; retained for inspection'}};Write-Error $_.Exception.Message -ErrorAction Continue}
finally{
 if($run -and $Action -ne 'Status'){$run.manifest.status=if($failed){'failed-retained'}else{'kept'};Write-ArtifactJson (Join-Path $run.path 'run.json') $run.manifest;if($run.lock){$run.lock.Dispose()}}
 foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
}
if($failed){exit 1}
