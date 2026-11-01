param([Parameter(Mandatory=$true)][string]$CandidateDirectory,[string]$NodeExe='E:/nodejs/node.exe')
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$candidate=Assert-ArtifactPath (Join-Path $root 'dist') ([IO.Path]::GetFullPath($CandidateDirectory));$inventory=Get-Content "$candidate/BUILD_INVENTORY.json" -Raw -Encoding UTF8|ConvertFrom-Json
foreach($f in $inventory.files){if((Get-FileHash -LiteralPath (Join-Path $candidate $f.file)).Hash -ne $f.sha256){throw 'Candidate hash mismatch'}}
$run=New-ArtifactRun $root 'complete-package-acceptance';$ok=$false;$failure='';$shell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe";$oldPass=$env:EMS_TEST_PASSWORD;$oldBrowser=$env:EMS_BROWSER_AUTO_OPEN;$devIdentity=$null;$checks=New-Object Collections.Generic.List[string]
function Check($Value,[string]$Label){if(-not $Value){throw $Label};$checks.Add($Label);Write-Host "PASS $Label"}
function Run([string]$File,$Arguments,[string]$Label){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$File)+$Arguments) "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
function Http([string]$Mode,[string]$Phase,[string]$Label){$port=@{server=8483;portable=8481;dev=8482}[$Mode];$code=Invoke-ArtifactNative $NodeExe @("$PSScriptRoot/test-complete-package-http.cjs","http://127.0.0.1:$port",$Mode,$Phase,"$($run.path)/$Label-results.json") "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label actual HTTP failed"}}
function Stop-Dev {if($devIdentity){$p=Test-ArtifactProcess $devIdentity;if($p){$p.Kill();if(-not $p.WaitForExit(15000)){throw 'Owned direct main did not stop'}};$script:devIdentity=$null}}
try{
 $env:EMS_TEST_PASSWORD='Synthetic'+[guid]::NewGuid().ToString('N')+'7';$env:EMS_BROWSER_AUTO_OPEN='false'
 foreach($mode in @('portable','server')){
  $archive=Join-Path $candidate (@{portable='EMS员工管理系统_v1.2.1_便携版_Windows_x64.zip';server='EMS员工管理系统_v1.2.1_服务器版_Windows_x64.zip'}[$mode])
  $fixture=Join-Path $run.path ($mode+' 中文 完整解压');[IO.Directory]::CreateDirectory($fixture)|Out-Null
  $zip=[IO.Compression.ZipFile]::OpenRead($archive);try{foreach($entry in $zip.Entries){Assert-ArtifactPath $fixture (Join-Path $fixture $entry.FullName)|Out-Null;if($entry.FullName -match '(^|/)(data|logs|dist|\.git)(/|$)' -or (($entry.ExternalAttributes -shr 16) -band 61440) -eq 40960){throw 'Private or linked archive entry'}}}finally{$zip.Dispose()}
  [IO.Compression.ZipFile]::ExtractToDirectory($archive,$fixture)
  Write-ArtifactJson "$fixture/test-fixture.json" @{run=$run.id;version='1.2.1';buildId='v1.2.1-complete-rc1'};Register-ArtifactInstance $run $fixture -RuntimeDirectory server-complete
  $manifest=Get-Content "$fixture/app/build-manifest-complete.json" -Raw -Encoding UTF8|ConvertFrom-Json
  Check ((Get-FileHash "$fixture/app/ems-1.2.1-complete.jar").Hash -eq $inventory.jarSha256 -and $manifest.sourceCommit -eq $inventory.sourceCommit) "$mode extracted JAR-source binding"
  Run "$root/launcher/test-first-setup-cancel.ps1" @('-Fixture',$fixture,'-Mode',$mode,'-RuntimeDirectory','server-complete') "$mode-first-cancel"
  Run "$PSScriptRoot/test-complete-first-start.ps1" @('-Fixture',$fixture,'-Mode',$mode) "$mode-first-start"
  Http $mode 'write' "$mode-write"
  $statePath="$fixture/data/complete/$mode/instance-upgrade.json";$before=Get-Content $statePath -Raw -Encoding UTF8|ConvertFrom-Json
  Run "$fixture/server-complete/start-server.ps1" @('-Mode',$mode,'-NoBrowser') "$mode-repeat-start"
  $after=Get-Content $statePath -Raw -Encoding UTF8|ConvertFrom-Json;Check ($before.app.id -eq $after.app.id -and $before.app.ticks -eq $after.app.ticks) "$mode repeat-start process unchanged"
  $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture/server-complete/initialize-server.ps1",'-Mode',$mode) "$($run.path)/logs/$mode-repeat-init.log";Check ($code -ne 0) "$mode repeat initialization refuses existing account"
  Run "$fixture/server-complete/stop-server.ps1" @('-Mode',$mode) "$mode-stop"
  $port=@{server=8483;portable=8481}[$mode];$listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$port)
  try{$listener.Start();$code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture/server-complete/start-server.ps1",'-Mode',$mode,'-NoBrowser') "$($run.path)/logs/$mode-port-conflict.log";Check ($code -ne 0) "$mode HTTP conflict explicit refusal"}finally{$listener.Stop()}
  Run "$fixture/server-complete/start-server.ps1" @('-Mode',$mode,'-NoBrowser') "$mode-restart";Http $mode 'read' "$mode-restart-read"
  if($mode -eq 'portable'){
   $devProbe=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,8482);try{$devProbe.Start()}finally{$devProbe.Stop()}
   Run "$fixture/server-complete/initialize-server.ps1" @('-Mode','dev','-DatabaseOnly') 'dev-initialize'
   Run "$root/launcher/seed-server-test.ps1" @('-Fixture',$fixture,'-Mode','dev','-RuntimeDirectory','server-complete') 'dev-seed'
   $info=New-Object Diagnostics.ProcessStartInfo;$info.FileName="$fixture/runtime/java/bin/java.exe";$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.WorkingDirectory=$fixture;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
   $cp="$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim();foreach($a in @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.EmsApplication')){$info.ArgumentList.Add($a)}
   foreach($line in [IO.File]::ReadAllLines("$fixture/data/complete/dev/idea.env")){if($line.Contains('=')){$pair=$line.Split('=',2);$info.Environment[$pair[0]]=$pair[1]}}
   $info.Environment['EMS_BROWSER_AUTO_OPEN']='false';$info.Environment.Remove('SPRING_PROFILES_ACTIVE')|Out-Null
   $dev=New-Object Diagnostics.Process;$dev.StartInfo=$info;$null=$dev.Start();$stdout=$dev.StandardOutput.ReadToEndAsync();$stderr=$dev.StandardError.ReadToEndAsync();Start-Sleep -Seconds 1
   $actual=Get-Process -Id $dev.Id;$devIdentity=@{id=$actual.Id;ticks=$actual.StartTime.ToUniversalTime().Ticks.ToString();exe=$actual.Path};Register-ArtifactProcess $run $actual 'developer-main'
   $ready=$false;for($i=0;$i -lt 90;$i++){if($dev.HasExited){throw 'Default main exited early'};try{if(@(Get-NetTCPConnection -LocalPort 8482 -State Listen -ErrorAction SilentlyContinue|Where-Object OwningProcess -eq $dev.Id).Count -gt 0 -and (Invoke-RestMethod 'http://127.0.0.1:8482/health' -TimeoutSec 2).status -eq 'UP'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1};Check $ready 'IDEA-equivalent default main with independent dev environment'
   Http 'dev' 'write' 'dev-write'
   Run "$fixture/server-complete/stop-server.ps1" @('-Mode','portable') 'portable-stop-with-dev'
   Check (-not $dev.HasExited -and (Invoke-RestMethod 'http://127.0.0.1:8482/health').status -eq 'UP') 'Portable stop preserves developer main and DB'
   Stop-Dev;[IO.File]::WriteAllText("$($run.path)/logs/dev-main.log",(Protect-ArtifactText ($stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult())))
   Run "$fixture/server-complete/stop-server.ps1" @('-Mode','dev') 'dev-stop'
   Run "$fixture/server-complete/start-server.ps1" @('-Mode',$mode,'-NoBrowser') 'portable-after-dev'
  }
  foreach($kind in @('application','database')){
   Run "$root/launcher/inject-server-test.ps1" @('-Fixture',$fixture,'-Mode',$mode,'-RuntimeDirectory','server-complete','-Kind',$kind) "$mode-$kind-crash"
   if($kind -eq 'database'){Run "$fixture/server-complete/stop-server.ps1" @('-Mode',$mode) "$mode-db-crash-stop"}
   Run "$fixture/server-complete/start-server.ps1" @('-Mode',$mode,'-NoBrowser') "$mode-$kind-restart";Http $mode 'read' "$mode-$kind-read"
  }
  Run "$fixture/server-complete/stop-server.ps1" @('-Mode',$mode) "$mode-pre-move-stop"
  $moved=Assert-ArtifactPath $run.path (Join-Path $run.path ($mode+' 已移动 中文 空格'));Assert-ArtifactPath $run.path $fixture|Out-Null;Move-Item -LiteralPath $fixture -Destination $moved;$fixture=$moved;Register-ArtifactInstance $run $fixture -RuntimeDirectory server-complete
  Run "$fixture/server-complete/start-server.ps1" @('-Mode',$mode,'-NoBrowser') "$mode-moved-start";Http $mode 'read' "$mode-moved-read"
  Run "$fixture/server-complete/stop-server.ps1" @('-Mode',$mode) "$mode-final-stop"
 }
 Write-ArtifactJson "$($run.path)/complete-package-results.json" @{passed=$true;count=$checks.Count;checks=@($checks);portable=$true;server=$true;directDevMain=$true;IDEAGui='manual';cleanWindows='manual';secondDevice='manual';candidate=$candidate;sourceCommit=$inventory.sourceCommit;jarSha256=$inventory.jarSha256}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{try{Stop-Dev}catch{$run.manifest.keep=$true;$failure+=' direct main cleanup uncertain'};$env:EMS_TEST_PASSWORD=$oldPass;$env:EMS_BROWSER_AUTO_OPEN=$oldBrowser;$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
