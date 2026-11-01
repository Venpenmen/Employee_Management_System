param([switch]$Lineage,[switch]$LineageOnly,[switch]$RestoreEngine)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$root/launcher/package-rules.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Run through the local continuation command runner'}
$run=New-ArtifactRun $root 'compatibility-catalog'
$fixture=Join-Path $run.path '结构校验 独立实例'
$ok=$false;$failure='';$state=$null
$priorUrl=$env:EMS_IT_URL;$priorPassword=$env:EMS_IT_PASSWORD
$priorJavaOptions=$env:JAVA_TOOL_OPTIONS
$priorControlledRoot=$env:EMS_CONTROLLED_ROOT
try{
 $logging=Join-Path $run.path 'test-logback.xml';[IO.File]::WriteAllText($logging,'<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>')
 $env:JAVA_TOOL_OPTIONS=$priorJavaOptions+' -Dlogback.configurationFile="'+$logging+'"'
 [IO.Directory]::CreateDirectory($fixture)|Out-Null
 foreach($file in Get-RcFiles $root 'runtime'){
  $destination=Join-Path $fixture $file.FullName.Substring($root.Length+1)
  [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))|Out-Null
  Copy-Item -LiteralPath $file.FullName -Destination $destination
 }
 Register-ArtifactInstance $run $fixture
 $shell=Join-Path $PSHOME 'pwsh.exe'
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$fixture/server/initialize-server.ps1",'-DatabaseOnly') "$($run.path)/logs/initialize.log"
 if($code -ne 0){throw 'Owned database initialization failed; see initialize.log'}
 . "$fixture/server/runtime-common.ps1"
 $state=Read-EmsState;Mount-EmsNativeRoot $state
 $secret=Read-EmsSecrets
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($state.dbPort)/$script:DbName`?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
 $env:EMS_IT_PASSWORD=$secret.root
 $env:EMS_CONTROLLED_ROOT=$fixture
 $cp="$root/backend/target/test-classes;$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim()
 $main='com.example.ems.upgrade.CompatibilityCatalogIT';$arguments=@("$($run.path)/catalog");$result='catalog'
 if($Lineage -or $LineageOnly -or $RestoreEngine){
  if(-not $LineageOnly -and -not $RestoreEngine){
  $code=Invoke-ArtifactNative $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.CompatibilityCatalogIT',"$($run.path)/catalog") "$($run.path)/logs/catalog.log"
  if($code -ne 0){throw 'Registered structure/history checks failed before lineage test'}
  Copy-Item -LiteralPath "$($run.path)/catalog/catalog-results.json" -Destination "$($run.path)/catalog-results.json"
  }
  $result='lineage';$main='com.example.ems.upgrade.LineageTrackerIT';$oldZip=Join-Path (Split-Path $root) 'employee-management-system_v1.2.0/dist/EMS_v1.2.0_Windows_Portable.zip'
  if($RestoreEngine){$result='restore-engine';$main='com.example.ems.upgrade.CompatibilityRestoreIT'}
  $expected=([IO.File]::ReadAllText($oldZip+'.sha256').Trim() -split '\s+')[0]
  if((Get-FileHash -LiteralPath $oldZip -Algorithm SHA256).Hash -ne $expected){throw 'Historical package SHA-256 mismatch'}
  $archive=[IO.Compression.ZipFile]::OpenRead($oldZip)
  try{
   $entries=@($archive.Entries|Where-Object {$_.FullName -match '^app/[^/]+\.jar$'})
   if($entries.Count -ne 1){throw 'Ambiguous historical application binary'}
   $legacyJar=Join-Path $run.path 'legacy-v120.jar';[IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0],$legacyJar)
  }finally{$archive.Dispose()}
  $arguments=@("$($run.path)/$result",$legacyJar)
 }
 $code=Invoke-ArtifactNative $script:JavaExe (@('-Dfile.encoding=UTF-8','-cp',$cp,$main)+$arguments) "$($run.path)/logs/$result.log"
 if($code -ne 0){throw "Live $result assertions failed; see $result.log"}
 Copy-Item -LiteralPath "$($run.path)/$result/$result-results.json" -Destination "$($run.path)/$result-results.json"
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 $env:EMS_IT_URL=$priorUrl;$env:EMS_IT_PASSWORD=$priorPassword;$env:JAVA_TOOL_OPTIONS=$priorJavaOptions;$env:EMS_CONTROLLED_ROOT=$priorControlledRoot
 try{
  if(Test-Path -LiteralPath "$fixture/data/server/instance-upgrade.json"){
   . "$fixture/server/runtime-common.ps1"
   $state=Read-EmsState
   if($state){Mount-EmsNativeRoot $state;Stop-EmsDatabase $state;Dismount-EmsNativeRoot $state;Save-EmsState $state}
   Clear-ClientConfigs
  }
 }catch{$failure+='; owned cleanup: '+$_.Exception.Message;$ok=$false}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure
 exit $code
}
