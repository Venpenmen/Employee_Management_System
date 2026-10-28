param([Parameter(Mandatory=$true)][string]$BackupFile,[string]$ContextPath,[switch]$KeepArtifacts)
$ErrorActionPreference='Stop'
$runtimeRoot=Split-Path $PSScriptRoot
. "$runtimeRoot\launcher\artifact-common.ps1"
$runtimeVersion=Get-ArtifactVersion $runtimeRoot
$root=$runtimeRoot
if($ContextPath){
 $parentManifest=Get-Content -LiteralPath $ContextPath -Raw -Encoding UTF8|ConvertFrom-Json
 $parent=Read-ArtifactContext $parentManifest.root $ContextPath
 Assert-ArtifactPath $parent.path $runtimeRoot|Out-Null
 $owner=Get-Process -Id $parent.manifest.ownerId -ErrorAction SilentlyContinue
 if(-not $owner -or $owner.StartTime.ToUniversalTime().Ticks.ToString() -ne $parent.manifest.ownerTicks){throw 'Parent verification context is not active'}
 $root=$parent.root
}
$run=New-ArtifactRun $root 'restore' -KeepArtifacts:$KeepArtifacts
$ok=$false;$failure='';$fixture=Join-Path $run.path 'r';$stopped=$false
$shell="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
try{
 if(-not(Test-Path -LiteralPath ($BackupFile+'.sha256')) -or (Get-FileHash -LiteralPath $BackupFile).Hash -ne [IO.File]::ReadAllText(($BackupFile+'.sha256')).Trim()){throw 'Backup hash mismatch'}
 New-Item -ItemType Directory -Path $fixture -Force|Out-Null
 foreach($name in @('runtime','app','bootstrap','server','launcher')){Copy-Item -LiteralPath "$runtimeRoot\$name" -Destination "$fixture\$name" -Recurse}
 Write-ArtifactJson "$fixture\restore-fixture.json" @{run=$run.id;root=$root}
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture\server\initialize-server.ps1",'-DatabaseOnly') (Join-Path $run.path 'logs/initialize.log')
 if($code -ne 0){throw 'Restore fixture initialization failed'}
 $job=Join-Path $fixture ('data/server/database-jobs/'+[Guid]::NewGuid().ToString())
 New-Item -ItemType Directory -Path $job -Force|Out-Null
 Copy-Item -LiteralPath $BackupFile -Destination (Join-Path $job 'input.emsbk')
 $secure=ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path (Split-Path $BackupFile) 'recovery.key.dpapi')).Trim())
 $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure);$old=$env:EMS_BACKUP_PASSWORD
 try{
  $env:EMS_BACKUP_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
  $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture\server\database-job.ps1",'-Mode','server','-Action','restore','-Job',$job,'-LocalTask') (Join-Path $run.path 'logs/restore.log')
 }finally{$env:EMS_BACKUP_PASSWORD=$old;[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
 if($code -ne 0){throw 'Encrypted restore verification failed'}
 $result=Get-Content -LiteralPath (Join-Path $job 'result.json') -Raw -Encoding UTF8|ConvertFrom-Json
 Write-ArtifactJson (Join-Path $run.path 'restore-results.json') @{passed=$true;tables=@($result.tables.PSObject.Properties).Count;encryption='AES256GCM';freshTarget=$true}
 Write-Host 'PASS: Backup SHA256 and every business-table row count verified in a fresh isolated database.'
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 try{
  if(Test-Path -LiteralPath "$fixture\data\server\instance-upgrade.json"){
   $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture\server\stop-server.ps1") (Join-Path $run.path 'logs/stop.log')
   if($code -ne 0){throw 'Restore instance stop failed'}
  }
  $stopped=$true
 }catch{$run.manifest.keep=$true;$failure+=' Cleanup blocked: '+$_.Exception.Message}
 $exitCode=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure
 if($ok -and -not $stopped){exit 2};exit $exitCode
}
