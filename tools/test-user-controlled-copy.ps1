param([string]$NodeExe='E:/nodejs/node.exe')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1";. "$root/launcher/package-rules.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed synthetic task required'}
$run=New-ArtifactRun $root 'user-copy-tool-test';$next=Join-Path $run.path '新版 测试';$work=Join-Path $run.path 'roundtrip';$shell=if(Test-Path -LiteralPath (Join-Path $PSHOME 'pwsh.exe')){Join-Path $PSHOME 'pwsh.exe'}else{"$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"};$copyId=$null;$ok=$false;$failure='';$saved=@{}
foreach($key in @('EMS_CONTROLLED_ROOT','EMS_IT_URL','EMS_IT_PASSWORD','EMS_COMPAT_WORK','EMS_DEMO_BUNDLE','EMS_LEGACY_COOKIE','JAVA_TOOL_OPTIONS')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
function Phase([string]$Name){
 . "$next/server/runtime-common.ps1";$state=Read-EmsState;Mount-EmsNativeRoot $state;$secret=Read-EmsSecrets
 $env:EMS_CONTROLLED_ROOT=$next;$env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($state.dbPort)/$script:DbName`?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";$env:EMS_IT_PASSWORD=$secret.root
 $cp="$root/backend/target/test-classes;$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim()
 if((Invoke-ArtifactNative $script:JavaExe @('-cp',$cp,'com.example.ems.upgrade.ControlledLegacyIT',$Name,$work) "$($run.path)/logs/$Name.log") -ne 0){throw "Phase $Name failed"}
}
function UserAction([string]$Name){
 $arguments=@('-NoProfile');if([IO.Path]::GetFileName($shell) -eq 'powershell.exe'){$arguments+=@('-ExecutionPolicy','Bypass')};$arguments+=@('-File',"$root/tools/test-user-copy-action.ps1",'-Action',$Name)
 if($Name -eq 'Create'){$arguments+=@('-BaseFile',"$work/bundle/base-v120.emsbk",'-ExtensionFile',"$work/bundle/extensions.emsbk",'-LegacyZip',(Join-Path (Split-Path $root) 'employee-management-system_v1.2.0/dist/EMS_v1.2.0_Windows_Portable.zip'))}else{$arguments+=@('-CopyId',$copyId)}
 $log="$($run.path)/logs/user-$Name.log";$code=Invoke-ArtifactNative $shell $arguments $log
 if($Name -eq 'Create'){$allocated=[regex]::Match([IO.File]::ReadAllText($log),'CopyId=(\d{8}-\d{6}-[a-f0-9]{12})');if($allocated.Success){$script:copyId=$allocated.Groups[1].Value}}
 if($code -ne 0){throw "User action $Name failed; inspect its log"};return $log
}
try{
 $logging=Join-Path $run.path 'logging.xml';[IO.File]::WriteAllText($logging,'<configuration><root level="WARN"/></configuration>');$env:JAVA_TOOL_OPTIONS=$saved.JAVA_TOOL_OPTIONS+' -Dlogback.configurationFile="'+$logging+'"'
 [IO.Directory]::CreateDirectory($next)|Out-Null
 foreach($file in Get-RcFiles $root 'runtime'){$destination=Join-Path $next $file.FullName.Substring($root.Length+1);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))|Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $destination}
 Register-ArtifactInstance $run $next
 $initArgs=@('-NoProfile');if([IO.Path]::GetFileName($shell) -eq 'powershell.exe'){$initArgs+=@('-ExecutionPolicy','Bypass')};$initArgs+=@('-File',"$next/server/initialize-server.ps1",'-DatabaseOnly')
 if((Invoke-ArtifactNative $shell $initArgs "$($run.path)/logs/initialize.log") -ne 0){throw 'Owned source initialization failed'}
 $env:EMS_DEMO_BUNDLE=Join-Path (Split-Path $root) 'employee-management-system_v1.2.0/dist/EMS_v1.2.0_DemoData.zip';Phase 'source'
 $createLog=UserAction 'Create';$match=[regex]::Match([IO.File]::ReadAllText($createLog),'CopyId=(\d{8}-\d{6}-[a-f0-9]{12})');if(-not $match.Success){throw 'User tool did not return a CopyId'};$copyId=$match.Groups[1].Value
 $null=UserAction 'Start';$null=UserAction 'Status'
 $copyRoot=Join-Path $root ('.release-work/runs/'+$copyId);$ready=Get-Content "$copyRoot/copy-ready.json" -Raw -Encoding UTF8|ConvertFrom-Json;$env:EMS_LEGACY_COOKIE=$ready.cookie
 if((Invoke-ArtifactNative $NodeExe @("$root/tools/test-old-roundtrip.cjs",'http://127.0.0.1:8485',"$($run.path)/user-tool-http-results.json","$work/test-auth.emsbk") "$($run.path)/logs/user-http.log") -ne 0){throw 'User-managed old runtime HTTP checks failed'}
 $null=UserAction 'ExportReturn';$null=UserAction 'Stop';$null=UserAction 'Start';$null=UserAction 'Stop'
 $returns=@(Get-ChildItem -LiteralPath "$copyRoot/private" -Directory -Filter 'return-*');if($returns.Count -ne 1){throw 'Return export count differs'}
 [IO.Directory]::CreateDirectory("$work/return")|Out-Null
 foreach($name in @('return-v120.emsbk','return-proof.emsbk')){Copy-Item -LiteralPath (Join-Path $returns[0].FullName $name) -Destination "$work/return/$name"}
 Phase 'restore'
 Write-ArtifactJson "$($run.path)/user-copy-tool-results.json" @{passed=$true;actualUserTool=$true;officialDemo=$true;createStartStatusReturnStopRestart=$true;isolatedRestore=$true}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 if($copyId){try{
  $null=UserAction 'Stop';$context=Join-Path $root ('.release-work/runs/'+$copyId+'/run.json');$copyRun=Recover-ArtifactRun $root $context
  if($copyRun.manifest.kind -ne 'controlled-legacy-user' -or $copyRun.manifest.ownerTaskId -ne $env:EMS_DEVELOPMENT_TASK_ID){throw 'User-copy test ownership mismatch'}
  # Only this synthetic test copy is collected. Normal user copies remain kept.
  $copyRun.manifest.keep=$false;$null=Complete-ArtifactRun $copyRun -Succeeded:$ok -OriginalError $failure
 }catch{$ok=$false;$failure+='; controlled test copy retained: '+$_.Exception.Message}}
 try{if(Test-Path "$next/data/server/instance-upgrade.json"){. "$next/server/runtime-common.ps1";$state=Read-EmsState;Mount-EmsNativeRoot $state;Stop-EmsDatabase $state;Dismount-EmsNativeRoot $state;Save-EmsState $state;Clear-ClientConfigs}}catch{$ok=$false;$failure+='; source cleanup retained: '+$_.Exception.Message}
 foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
 . "$root/launcher/artifact-common.ps1";$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
