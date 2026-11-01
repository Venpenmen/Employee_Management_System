param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$ProjectRoot,[switch]$SecurityOnly,[ValidateSet('server','portable','dev')][string]$Mode='server',[ValidateSet('server','server-complete')][string]$RuntimeDirectory='server')
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath "$Fixture/test-fixture.json")){throw 'Synthetic fixture required'}
. "$Fixture/$RuntimeDirectory/runtime-common.ps1" -Mode $Mode
$lock=$null;$old=@{}
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s;$secret=Read-EmsSecrets
 $cp="$ProjectRoot/backend/target/test-classes;$ProjectRoot/backend/target/classes;"+[IO.File]::ReadAllText("$ProjectRoot/backend/target/dev-classpath.txt").Trim()
 $vars=@{EMS_IT_URL=("jdbc:mysql://127.0.0.1:"+$s.dbPort+"/"+$script:DbName+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");EMS_IT_PASSWORD=$secret.root;EMS_DB_PASSWORD=$secret.app}
 if($RuntimeDirectory -eq 'server-complete'){$vars['EMS_IT_ROOT']=$Fixture;$vars['EMS_IT_MODE']=$Mode}
 foreach($key in $vars.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$vars[$key],'Process')}
 if($SecurityOnly){Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.security.Rc2SecurityDatabaseIT') -TimeoutSeconds 300|Write-Host}
 else{
  $work=Join-Path $script:DataDir 'migration-checks';New-Item -ItemType Directory -Path $work -Force|Out-Null
  $old120=$env:EMS_MIG_120_JAR;if(-not $old120){$old120=Join-Path (Split-Path $ProjectRoot) 'employee-management-system_v1.2.0/app/ems-1.2.0-rc1.jar'}
  $oldRc1=$env:EMS_MIG_RC1_JAR;if(-not $oldRc1){$oldRc1=Join-Path $ProjectRoot 'app/ems-1.2.1-next-rc1.jar'}
  if(-not(Test-Path $old120) -or -not(Test-Path $oldRc1)){throw 'Preserved immutable historical exporter JAR missing'}
  $hash120=$env:EMS_MIG_120_SHA256;$hashRc1=$env:EMS_MIG_RC1_SHA256
  if(-not $hash120){$manifest120=Join-Path (Split-Path $old120) 'build-manifest.json';if(Test-Path -LiteralPath $manifest120){$hash120=(Get-Content -LiteralPath $manifest120 -Raw -Encoding UTF8|ConvertFrom-Json).sha256}}
  if(-not $hashRc1){
   Add-Type -AssemblyName System.IO.Compression.FileSystem
   $reference=Join-Path $ProjectRoot 'dist/EMS员工管理系统_v1.2.1-next_服务器版_Windows_x64.zip'
   if(Test-Path -LiteralPath $reference){$zip=[IO.Compression.ZipFile]::OpenRead($reference);try{$reader=New-Object IO.StreamReader($zip.GetEntry('app/build-manifest.json').Open());try{$hashRc1=($reader.ReadToEnd()|ConvertFrom-Json).sha256}finally{$reader.Dispose()}}finally{$zip.Dispose()}}
  }
  if(-not $hash120 -or -not $hashRc1 -or (Get-FileHash -LiteralPath $old120).Hash -ne $hash120 -or (Get-FileHash -LiteralPath $oldRc1).Hash -ne $hashRc1){throw 'Historical JAR authenticity baseline unavailable/mismatched; provide trusted EMS_MIG_120_SHA256 and EMS_MIG_RC1_SHA256; no code loaded'}

  Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.Rc2MigrationIT',$work,$old120,$oldRc1) -TimeoutSeconds 600|Write-Host
  $snapshot=Join-Path $work 'v3.emsbk';$snapshotHash=(Get-FileHash -LiteralPath $snapshot).Hash
  & "$ProjectRoot/$RuntimeDirectory/check-upgrade.ps1" -BackupFile $snapshot -Mode $Mode -Sha256 $snapshotHash|Out-Null
  Write-Host 'PASS: MIG-15 upgrade preflight validates actual encrypted backup and free space'
  $badHash=$false;try{& "$ProjectRoot/$RuntimeDirectory/check-upgrade.ps1" -BackupFile $snapshot -Mode $Mode -Sha256 ('0'*64)|Out-Null}catch{$badHash=$true};if(-not $badHash){throw 'Upgrade preflight accepted bad backup hash'}
  Write-Host 'PASS: MIG-15 failed backup validation stops upgrade before database actions'
  $originalDb=$script:DbName;$tempName='ems_next_'+$Mode+'_v121_g'+[Guid]::NewGuid().ToString('N').Substring(0,12)
  if($tempName -notmatch ('^ems_next_'+$Mode+'_v121_g[a-f0-9]{12}$')){throw 'Invalid owned guard-test schema'}
  $rootConfig=Write-ClientConfig $s
  Invoke-EmsTool $script:MysqlExe @("--defaults-file=$rootConfig",'-e',("CREATE DATABASE "+$tempName+" CHARACTER SET utf8mb4"))|Out-Null
  try{
   $v2url=$env:EMS_IT_URL.Replace('/'+$originalDb+'?','/'+$tempName+'?')
   Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.Rc2LauncherGuardIT',$v2url)|Out-Null
   $script:DbName=$tempName;$rejected=$false;try{Update-EmsSchema $s}catch{$rejected=$true}
   if(-not $rejected){throw 'Launcher automatically upgraded V2 without recovery point'}
   $version=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$rootConfig",'-N',$tempName,'-e',"SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
   if($version.Trim() -ne '2'){throw 'V2 changed during refused launcher upgrade'}
   Write-Host 'PASS: MIG-06 launcher rejects real V2 fixture before any automatic migration; V2 remains unchanged'
  }finally{$script:DbName=$originalDb;Invoke-EmsTool $script:MysqlExe @("--defaults-file=$rootConfig",'-e',("DROP DATABASE "+$tempName))|Out-Null}

 }
}finally{foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')};Clear-ClientConfigs;if($lock){$lock.Dispose()}}
