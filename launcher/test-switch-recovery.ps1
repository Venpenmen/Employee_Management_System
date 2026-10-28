$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$run=New-ArtifactRun $root 'switch-recovery-tests';$ok=$false;$failure='';$checks=New-Object Collections.Generic.List[string]
try{
 foreach($case in @('terminal-rollback','interrupted-terminal','stale-rollback','stale-success','failed-before-switch','foreign-pointer','foreign-marker','dead-owner','live-owner')){
  $f=Join-Path $run.path $case
  New-Item -ItemType Directory -Path "$f/server","$f/launcher" -Force|Out-Null
  Copy-Item -LiteralPath "$root/server/runtime-common.ps1" -Destination "$f/server/runtime-common.ps1"
  Copy-Item -LiteralPath "$root/launcher/artifact-common.ps1" -Destination "$f/launcher/artifact-common.ps1"
  . "$f/server/runtime-common.ps1" -Mode server
  $id=[Guid]::NewGuid().ToString();$job=Join-Path $script:DataDir ('database-jobs/'+$id)
  New-Item -ItemType Directory -Path $job -Force|Out-Null
  $old='ems_up_server_v120_g111111111111';$target='ems_up_server_v120_g222222222222';$new='ems_up_server_v120_g333333333333'
  $phase='POINTER_SWITCHED';$status='SWITCHING';$pointer=$target
  switch($case){
   'terminal-rollback' {$phase='ROLLED_BACK';$status='ROLLED_BACK';$pointer=$new}
   'interrupted-terminal' {$phase='ROLLED_BACK_AFTER_INTERRUPTION';$status='ROLLED_BACK';$pointer=$new}
   'stale-rollback' {$status='ROLLED_BACK';$pointer=$new}
   'stale-success' {$status='SUCCEEDED';$pointer=$new}
   'failed-before-switch' {$phase='PREPARED';$status='FAILED';$pointer=$new}
   'foreign-pointer' {$pointer=$new}
  }
  $owner=Get-Process -Id $PID
  $j=@{old=$old;target=$target;phase=$phase;mode='server';buildId='v1.2.0-rc1';ownerId=2147483646;ownerTicks='1';ownerExe=$owner.Path}
  if($case -eq 'live-owner'){$j.ownerId=$PID;$j.ownerTicks=$owner.StartTime.ToUniversalTime().Ticks.ToString()}
  Write-ArtifactJson "$job/switch-journal.json" $j
  Write-ArtifactJson "$job/status.json" @{state=$status;message='synthetic'}
  Write-Utf8 "$script:DataDir/active-schema" $pointer
  Write-Utf8 "$script:DataDir/maintenance.flag" $id
  if($case -eq 'foreign-marker'){Write-Utf8 "$script:DataDir/maintenance.flag" ([Guid]::NewGuid().ToString())}
  Write-Utf8 "$script:DataDir/quiesced" $id
  $rejected=$false;try{Recover-EmsPendingSwitch $null}catch{$rejected=$true}
  if($case -in @('foreign-pointer','foreign-marker','live-owner')){
   if(-not $rejected -or [IO.File]::ReadAllText("$script:DataDir/active-schema") -ne $pointer -or -not(Test-Path "$script:DataDir/maintenance.flag")){throw 'Unknown/live task must be preserved'}
  }else{
   if($rejected){throw 'Owned recovery unexpectedly rejected'}
   $expected=if($case -eq 'dead-owner'){$old}else{$pointer}
   if([IO.File]::ReadAllText("$script:DataDir/active-schema") -ne $expected -or (Test-Path "$script:DataDir/maintenance.flag")){throw 'Terminal recovery changed newer data or retained own marker'}
  }
  $checks.Add($case);Write-Host "PASS: $case"
 }
 Write-ArtifactJson (Join-Path $run.path 'switch-recovery-results.json') @{passed=$true;count=$checks.Count;checks=@($checks)}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
