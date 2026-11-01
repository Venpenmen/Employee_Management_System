param([string]$NodeExe='E:/nodejs/node.exe')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1";. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-three-modes';$fixture=Join-Path $run.path '三模式 同时运行';$ok=$false;$failure='';$prior=$env:EMS_TEST_PASSWORD;$browser=$env:EMS_BROWSER_AUTO_OPEN;$shell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe";$checks=New-Object Collections.Generic.List[string]
function Run([string]$File,$Arguments,[string]$Label){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$File)+$Arguments) "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
function Check($Value,[string]$Label){if(-not $Value){throw $Label};$checks.Add($Label);Write-Host "PASS $Label"}
function Http([string]$Mode,[string]$Phase,[string]$Label){$port=@{server=8483;portable=8481;dev=8482}[$Mode];$code=Invoke-ArtifactNative $NodeExe @("$PSScriptRoot/test-complete-package-http.cjs","http://127.0.0.1:$port",$Mode,$Phase,"$($run.path)/$Label-results.json") "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
try{
 Copy-CompleteFixture $run $fixture
 # Dev deliberately requires compiled source, unlike the two end-user modes.
 # Copy the current offline build's main classes, verifying every byte against
 # the candidate JAR; no old installation or runtime source dependency is used.
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $classes=Join-Path $root 'backend/target/classes';$jar=[IO.Compression.ZipFile]::OpenRead("$fixture/app/ems-1.2.1-complete.jar")
 try{foreach($file in Get-ArtifactFiles $classes -Strict){
  $relative=$file.FullName.Substring($classes.Length+1).Replace('\','/');$entry=$jar.GetEntry('BOOT-INF/classes/'+$relative)
  # Spring Boot repackaging intentionally relocates this bootstrap metadata to
  # the archive root. Still compare exact bytes; no unknown resource fallback.
  if(-not $entry -and $relative -eq 'META-INF/spring.factories'){$entry=$jar.GetEntry($relative)}
  if(-not $entry){throw 'Unbound development class/resource'}
  $stream=$entry.Open();$sha=[Security.Cryptography.SHA256]::Create();try{$hash=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')}finally{$stream.Dispose();$sha.Dispose()};if($hash -ne (Get-FileHash $file.FullName).Hash){throw 'Dev compilation differs from candidate JAR'}
  $dest=Assert-ArtifactPath $fixture (Join-Path $fixture ('backend/target/classes/'+$relative));[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))|Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $dest
 }}finally{$jar.Dispose()}
 Copy-Item -LiteralPath "$root/backend/target/dev-classpath.txt" -Destination "$fixture/backend/target/dev-classpath.txt"
 $env:EMS_TEST_PASSWORD='Synthetic'+[guid]::NewGuid().ToString('N')+'7';$env:EMS_BROWSER_AUTO_OPEN='false'
 $states=@{}
 foreach($mode in @('server','portable','dev')){
  Run "$fixture/server-complete/initialize-server.ps1" @('-Mode',$mode,'-DatabaseOnly') "$mode-init"
  Run "$root/launcher/seed-server-test.ps1" @('-Fixture',$fixture,'-Mode',$mode,'-RuntimeDirectory','server-complete') "$mode-seed"
  Run "$fixture/server-complete/start-server.ps1" @('-Mode',$mode,'-NoBrowser') "$mode-start"
  $states[$mode]=Get-Content "$fixture/data/complete/$mode/instance-upgrade.json" -Raw -Encoding UTF8|ConvertFrom-Json
  Http $mode 'write' "$mode-independent-write"
 }
 Check (@($states.Values|ForEach-Object dbPort|Select-Object -Unique).Count -eq 3) 'Three distinct owned MySQL ports'
 Check (@($states.Values|ForEach-Object {$_.database.id}|Select-Object -Unique).Count -eq 3) 'Three distinct database process identities'
 Check (@($states.Values|ForEach-Object {$_.app.id}|Select-Object -Unique).Count -eq 3) 'Three applications simultaneously live'
 foreach($mode in @('portable','server','dev')){
  Run "$fixture/server-complete/stop-server.ps1" @('-Mode',$mode) "$mode-exclusive-stop"
  foreach($other in @('portable','server','dev')|Where-Object {$_ -ne $mode}){
   Run "$PSScriptRoot/test-complete-identity.ps1" @('-Fixture',$fixture,'-Mode',$other,'-ExpectedApp',($states[$other].app.id.ToString()+':'+$states[$other].app.ticks),'-ExpectedDatabase',($states[$other].database.id.ToString()+':'+$states[$other].database.ticks)) "$mode-stop-$other-identity"
   Check ([IO.File]::ReadAllText("$($run.path)/logs/$mode-stop-$other-identity.log") -match 'PASS exact peer PID, start time, canonical executable and verified native-drive alias preserved') "$mode stop preserves $other verified process identities and executable alias"
   Http $other 'read' "$mode-stop-$other-read"
  }
  Run "$fixture/server-complete/start-server.ps1" @('-Mode',$mode,'-NoBrowser') "$mode-exclusive-restart"
  $states[$mode]=Get-Content "$fixture/data/complete/$mode/instance-upgrade.json" -Raw -Encoding UTF8|ConvertFrom-Json
  Http $mode 'read' "$mode-own-persistence-read"
 }
 Write-ArtifactJson "$($run.path)/three-mode-results.json" @{passed=$true;count=$checks.Count;checks=@($checks);simultaneousModes=@('portable','server','dev');separateCookiesAndProfileValues=$true;scope='owned fixture; default main CLI separately validated; no IDEA GUI/real device claim'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$env:EMS_TEST_PASSWORD=$prior;$env:EMS_BROWSER_AUTO_OPEN=$browser;$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
