param([Parameter(Mandatory=$true)][string]$LegacyRoot,[ValidateSet('server','portable','dev')][string]$Mode='server',[ValidateSet('ems_db','ems_server_v120','ems_portable_v120','ems_dev_v120')][string]$LegacySchema)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$run=New-ArtifactRun $script:EmsRoot 'legacy-upgrade';$lock=$null;$coldProcess=$null;$legacyConfig=$null;$success=$false;$failure='';$state=$null;$oldEnv=@{}
try{
 # Execution boundary: protected sibling projects are never eligible sources. In-place installation
 # or an explicitly prepared test installation underneath this project are the only accepted paths.
 $source=[IO.Path]::GetFullPath($LegacyRoot).TrimEnd('\')
 if($source -ne $script:EmsRoot){Assert-ArtifactPath $script:EmsRoot $source|Out-Null}
 $sourceData=Join-Path $source 'data/mysql'
 if(-not(Test-Path -LiteralPath $sourceData)){$sourceData=Join-Path $source ("data/"+$Mode+"/mysql")}
 Assert-ArtifactPath $source $sourceData|Out-Null
 if(-not(Test-Path -LiteralPath (Join-Path $sourceData 'ibdata1'))){throw 'Legacy MySQL directory not found'}
 $oldData=Split-Path $sourceData
 foreach($file in @((Join-Path $oldData 'instance.json'))){
  if(Test-Path -LiteralPath $file){
   $old=Get-Content -LiteralPath $file -Raw -Encoding UTF8|ConvertFrom-Json
   foreach($identity in @($old.app,$old.database)){
    if($identity){$p=Get-Process -Id $identity.id -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks.ToString() -eq [string]$identity.ticks){throw 'Stop the legacy installation with its own stop entrypoint before upgrading'}}
   }
  }
 }
 $oldEngine=Join-Path $source 'runtime/mysql/bin/mysqld.exe'
 if((Get-FileHash -LiteralPath $oldEngine).Hash -ne (Get-FileHash -LiteralPath $script:MysqldExe).Hash){throw 'Legacy engine hash differs; no unverified physical conversion'}
 $lock=Enter-EmsLock;$state=Read-EmsState;if(-not $state){$state=New-EmsState}
 if(Get-OwnedProcess $state.app){throw 'Stop only this new application before local installation upgrade'}
 Mount-EmsNativeRoot $state;Save-EmsState $state
 $sourceNative=Get-NativePath $sourceData
 $guard=[IO.File]::Open((Join-Path $sourceNative 'ibdata1'),'Open','Read','Read')
 try{
  $files=@(Get-ArtifactFiles $sourceNative -Strict)
  $bytes=[long](($files|Measure-Object Length -Sum).Sum)
  $drive=New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($script:EmsRoot))
  if($drive.AvailableFreeSpace -lt ($bytes*3+256MB)){throw 'Insufficient free disk space for cold copy and isolated target'}
  $fingerprint=($files|Sort-Object FullName|ForEach-Object {$_.FullName.Substring($sourceNative.Length)+':'+(Get-FileHash -LiteralPath $_.FullName).Hash}) -join ';'
  $sha=[Security.Cryptography.SHA256]::Create();try{$sourceHash=[BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($fingerprint))).Replace('-','')}finally{$sha.Dispose()}
  $stamp=Join-Path $script:DataDir 'legacy-source.sha256'
  if((Test-Path -LiteralPath $stamp) -and [IO.File]::ReadAllText($stamp).Trim() -eq $sourceHash){Write-Host 'Identical legacy installation already upgraded; no data imported again';$success=$true;return}
  if(Test-Path -LiteralPath "$script:DataDir/active-schema"){throw 'A different upgraded database already exists; use authenticated import instead'}
  $cold=Join-Path $run.path 'cold';New-Item -ItemType Directory -Path $cold|Out-Null
  $coldNative=Get-NativePath $cold
  foreach($file in $files){$relative=$file.FullName.Substring($sourceNative.Length+1);$dest=Join-Path $coldNative $relative;New-Item -ItemType Directory -Path (Split-Path $dest) -Force|Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $dest}
 }finally{$guard.Dispose()}
 $plain=Join-Path $oldData 'credentials.json';$protected=Join-Path $oldData 'credentials.dpapi.json'
 if(Test-Path -LiteralPath $protected){$creds=Get-Content -LiteralPath $protected -Raw -Encoding UTF8|ConvertFrom-Json;$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((ConvertTo-SecureString $creds.root));try{$legacyPassword=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}}
 elseif(Test-Path -LiteralPath $plain){$legacyPassword=(Get-Content -LiteralPath $plain -Raw -Encoding UTF8|ConvertFrom-Json).root}else{throw 'Legacy credential metadata is unavailable'}
 $port=Find-FreePort 16506 16530 16530
 $legacyConfig=Join-Path $run.path 'legacy-client.cnf'
 Write-Utf8 $legacyConfig ((@('[client]','host=127.0.0.1',('port='+$port),'protocol=TCP','user=root',('password="'+$legacyPassword.Replace('\','\\').Replace('"','\"')+'"')) -join [Environment]::NewLine))
 $log=Get-NativePath (Join-Path $run.path 'cold-mysql.log')
 $arguments=@('--no-defaults','--no-monitor',('--basedir='+(Get-NativePath $script:MysqlHome)),('--datadir='+$coldNative),('--port='+$port),'--bind-address=127.0.0.1','--mysqlx=OFF','--skip-log-bin',('--log-error='+$log),'--innodb-buffer-pool-size=64M')
 $quoted=($arguments|ForEach-Object {Quote-EmsArgument $_}) -join ' '
 $coldProcess=Start-Process -FilePath $script:MysqldExe -ArgumentList $quoted -WindowStyle Hidden -PassThru
 $coldIdentity=Get-ProcessIdentity $coldProcess $script:MysqldExe;Register-ArtifactProcess $run $coldProcess 'legacy-cold-database'
 $up=$false
 for($i=0;$i -lt 60;$i++){try{Invoke-EmsTool $script:MysqlExe @("--defaults-file=$legacyConfig",'-N','-e','SELECT 1') -TimeoutSeconds 3|Out-Null;$up=$true;break}catch{Start-Sleep -Seconds 1}}
 if(-not $up){throw 'Cold copy could not start; original data was not touched'}
 if(-not $LegacySchema){
  $names=(Invoke-EmsTool $script:MysqlExe @("--defaults-file=$legacyConfig",'-N','-e','SHOW DATABASES')) -split '\r?\n'
  $matches=@($names|Where-Object {$_ -in @('ems_db','ems_server_v120','ems_portable_v120','ems_dev_v120')})
  if($matches.Count -ne 1){throw 'Select LegacySchema explicitly; ambiguous legacy ownership'}
  $LegacySchema=$matches[0]
 }
 $job=Join-Path $script:DataDir ('database-jobs/'+[Guid]::NewGuid().ToString());New-Item -ItemType Directory -Path $job -Force|Out-Null
 $pass=New-EmsSecret
 Write-Utf8 (Join-Path $job 'recovery.key.dpapi') (ConvertFrom-SecureString (ConvertTo-SecureString $pass -AsPlainText -Force))
 $sourceVersion=if($old -and $old.version -in @('1.1.3','1.2.0')){[string]$old.version}else{'legacy-unversioned'}
 $vars=@{EMS_SOURCE_VERSION=$sourceVersion;EMS_LEGACY_PORT=[string]$port;EMS_LEGACY_SCHEMA=$LegacySchema;EMS_LEGACY_PASSWORD=$legacyPassword;EMS_BACKUP_PASSWORD=$pass;EMS_MAINT_ROOT=$script:EmsRoot;EMS_MAINT_JOB=$job}
 foreach($key in $vars.Keys){$oldEnv[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$vars[$key],'Process')}
 Invoke-EmsTool $script:JavaExe @('-Dloader.main=com.example.ems.upgrade.LegacyExportMain','-cp',$script:JarFile,'org.springframework.boot.loader.launch.PropertiesLauncher') -TimeoutSeconds 600|Write-Host
 Invoke-EmsTool $script:MysqlExe @("--defaults-file=$legacyConfig",'-e','SHUTDOWN')|Out-Null
 if(-not $coldProcess.WaitForExit(30000)){throw 'Cold copy stop was not confirmed'};$coldProcess=$null
 Start-LocalDatabase $state
 $cnf=Write-ClientConfig $state
 $count=[long](Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N',$script:DbName,'-e','SELECT COUNT(*) FROM employee'))
 if($count -ne 0){throw 'Target already contains employees; no automatic overwrite'}
 Invoke-EmsMaintenance 'restore' $state $job
 $result=Get-Content -LiteralPath (Join-Path $job 'result.json') -Raw -Encoding UTF8|ConvertFrom-Json
 if($result.target -notmatch ('^ems_up_'+$Mode+'_v120_g[a-f0-9]{12}$')){throw 'Unexpected target'}
 Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-e',"GRANT SELECT,INSERT,UPDATE,DELETE ON $($result.target).* TO 'ems_app'@'127.0.0.1';")|Out-Null
 Write-Utf8 "$script:DataDir/active-schema.tmp" $result.target;Move-Item -LiteralPath "$script:DataDir/active-schema.tmp" -Destination "$script:DataDir/active-schema" -Force
 Write-Utf8 "$script:DataDir/legacy-source.sha256" $sourceHash
 Write-ArtifactJson (Join-Path $run.path 'upgrade-results.json') @{passed=$true;sourceSchema=$LegacySchema;sourceSha256=$sourceHash;target=$result.target;physicalSource='never started or written';engineVerified=$true}
 $success=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 if($coldProcess){try{if(Get-OwnedProcess $coldIdentity){Invoke-EmsTool $script:MysqlExe @("--defaults-file=$legacyConfig",'-e','SHUTDOWN')|Out-Null;if(-not $coldProcess.WaitForExit(30000)){throw 'Cold copy stop timed out'}}}catch{$run.manifest.keep=$true;$failure+=' Cold process stop not confirmed'}}
 foreach($key in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($key,$oldEnv[$key],'Process')}
 if($legacyConfig){Remove-Item -LiteralPath $legacyConfig -Force -ErrorAction SilentlyContinue}
 Clear-ClientConfigs;if($lock){$lock.Dispose()}
 $code=Complete-ArtifactRun $run -Succeeded:$success -OriginalError $failure
 exit $code
}
