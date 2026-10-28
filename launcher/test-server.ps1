param([string]$ZipPath,[string]$NodeExe,[string]$BrowserExe='C:\Program Files\Google\Chrome\Application\chrome.exe',[switch]$KeepArtifacts)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$PSScriptRoot\artifact-common.ps1"
$run=New-ArtifactRun $root 'server-acceptance' -KeepArtifacts:$KeepArtifacts
$localIpResult='pending';$fixture=Join-Path $run.path '验收 空格目录';$success=$false;$failure='';$oldPath=$env:PATH;$oldPassword=$env:EMS_TEST_PASSWORD;$oldBrowser=$env:EMS_BROWSER_AUTO_OPEN
$shell="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
function Run([string]$Script,[string[]]$Arguments,[string]$Log,[int]$Expected=0){
 $code=Invoke-ArtifactNative $shell (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$Script)+$Arguments) (Join-Path $run.path ('logs/'+$Log+'.log'))
 if($code -ne $Expected){throw "$Log failed: exit $code (expected $Expected)"}
}
function Free-Port {
 $l=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);$l.Start();$p=$l.LocalEndpoint.Port;$l.Stop();return $p
}
try{
 $NodeExe=Resolve-ArtifactTool $NodeExe 'node.exe';if(-not(Test-Path -LiteralPath $BrowserExe)){throw 'Chrome/Edge is required for browser acceptance'}
 $env:PATH="$env:SystemRoot\System32;$env:SystemRoot;$env:SystemRoot\System32\WindowsPowerShell\v1.0"
 New-Item -ItemType Directory -Path $fixture -Force|Out-Null
 if($ZipPath){
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath($ZipPath),$fixture)
 }else{
  foreach($name in @('runtime','app','bootstrap','server','launcher')){Copy-Item -LiteralPath "$root\$name" -Destination "$fixture\$name" -Recurse}
 }
 New-Item -ItemType Directory -Path "$fixture\backend\target\classes" -Force|Out-Null
 Copy-Item -Path "$root\backend\target\classes\*" -Destination "$fixture\backend\target\classes" -Recurse
 Copy-Item -LiteralPath "$root\backend\target\dev-classpath.txt" -Destination "$fixture\backend\target\dev-classpath.txt"
 Write-ArtifactJson "$fixture\test-fixture.json" @{run=$run.id;version='1.2.0'}
 Register-ArtifactInstance $run $fixture
 $env:EMS_TEST_PASSWORD='Qa'+[Guid]::NewGuid().ToString('N')+'7';$env:EMS_BROWSER_AUTO_OPEN='false'
 Run "$fixture\server\initialize-server.ps1" @('-DatabaseOnly') 'initialize'
 Run "$root\launcher\test-upgrade-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root) 'upgrade-database'
 Run "$root\launcher\seed-server-test.ps1" @('-Fixture',$fixture) 'seed'
 Run "$root\launcher\test-session-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root) 'session-database'
 $port=Free-Port
 $args=@('-Address','127.0.0.1','-Port',[string]$port,'-NoBrowser')
 Run "$fixture\server\start-server.ps1" $args 'start'
 $before=Get-Content -LiteralPath "$fixture\data\server\instance-upgrade.json" -Raw -Encoding UTF8|ConvertFrom-Json
 Run "$fixture\server\start-server.ps1" $args 'repeat-start'
 $after=Get-Content -LiteralPath "$fixture\data\server\instance-upgrade.json" -Raw -Encoding UTF8|ConvertFrom-Json
 if($before.app.id -ne $after.app.id){throw 'Repeat start created another JVM'}
 $listeners=@(Get-NetTCPConnection -State Listen -OwningProcess $after.database.id -ErrorAction Stop)
 if(-not $listeners.Count -or @($listeners|Where-Object LocalAddress -ne '127.0.0.1').Count){throw 'Bundled MySQL is not restricted to loopback'}
 $base="http://127.0.0.1:$port"
 $code=Invoke-ArtifactNative $NodeExe @("$root\launcher\test-server-http.cjs",$base,(Join-Path $run.path 'http-results.json')) (Join-Path $run.path 'logs/http.log')
 if($code -ne 0){throw 'HTTP business/security regression failed'}
 Run "$root\launcher\inspect-server-test.ps1" @('-Fixture',$fixture,'-OutputDir',$run.path) 'database-consistency'
 & "$root\launcher\verify-excel.ps1" -Workbook "$($run.path)\salary.xlsx" -ExpectedTsv "$($run.path)\salary-private.tsv" -OutputFile "$($run.path)\excel-results.txt"
 $code=Invoke-ArtifactNative $NodeExe @("$root\launcher\test-browser-v112.cjs",$base,'260003','-',(Join-Path $run.path 'browser-results.txt'),$BrowserExe,(Join-Path $run.path 'browser-profile')) (Join-Path $run.path 'logs/browser.log')
 if($code -ne 0){throw 'Browser interaction regression failed'}

 $code=Invoke-ArtifactNative $NodeExe @("$root\launcher\test-session-http.cjs",$base,(Join-Path $run.path 'session-http-results.json')) (Join-Path $run.path 'logs/session-http.log')
 if($code -ne 0){throw 'Session HTTP regression failed'}
 $code=Invoke-ArtifactNative $NodeExe @("$root\launcher\test-session-browser.cjs",$base,(Join-Path $run.path 'session-browser-results.json'),$BrowserExe,(Join-Path $run.path 'session-browser-profile')) (Join-Path $run.path 'logs/session-browser.log')
 if($code -ne 0){throw 'Session browser regression failed'}
 $code=Invoke-ArtifactNative $NodeExe @("$root\launcher\test-database-http.cjs",$base,(Join-Path $run.path 'database-http-results.json'),$fixture) (Join-Path $run.path 'logs/database-http.log')
 if($code -ne 0){throw 'Database management HTTP checks failed'}
 Run "$root\launcher\generate-recruitment-fixture.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root) 'recruitment-fixture'
 $code=Invoke-ArtifactNative $NodeExe @("$root\launcher\test-business-complete.cjs",$base,(Join-Path $run.path 'business-complete-results.json'),(Join-Path $fixture 'data/server/recruitment-private.xlsx')) (Join-Path $run.path 'logs/business-complete.log')
 if($code -ne 0){throw 'Expanded business regression failed'}
 Run "$root\launcher\inspect-expanded-business.ps1" @('-Fixture',$fixture) 'expanded-business-database'
 Run "$root\launcher\inject-server-test.ps1" @('-Fixture',$fixture,'-Kind','application') 'application-crash'
 Run "$fixture\server\start-server.ps1" $args 'application-crash-restart'
 Run "$root\launcher\inject-server-test.ps1" @('-Fixture',$fixture,'-Kind','database') 'database-crash'
 $down=$false
 try{Invoke-RestMethod "$base/health" -TimeoutSec 10|Out-Null}catch{
  if([int]$_.Exception.Response.StatusCode -eq 503){$down=$true}
 }
 if(-not $down){throw 'Unavailable database did not produce minimal503 health'}
 Run "$fixture\server\stop-server.ps1" @() 'stop-after-database-crash'
 Run "$fixture\server\start-server.ps1" $args 'database-crash-restart'
 # Back up with application stopped, leaving only its independent database running.
 Run "$fixture\server\stop-server.ps1" @() 'stop-before-backup'
 Run "$fixture\server\initialize-server.ps1" @('-DatabaseOnly') 'reuse-initialize'
 Run "$fixture\server\backup-database.ps1" @() 'backup'
 $backup=Get-ChildItem -LiteralPath "$fixture\data\server\backups" -Recurse -Filter '*.emsbk'|Sort-Object LastWriteTimeUtc -Descending|Select-Object -First 1
 Run "$fixture\server\verify-restore.ps1" @('-BackupFile',$backup.FullName,'-ContextPath',(Join-Path $run.path 'run.json')) 'restore-verification'
 Run "$fixture\server\start-server.ps1" $args 'restart'
 if((Invoke-RestMethod "$base/health").status -ne 'UP'){throw 'Restart readiness failed'}
 # All three modes must coexist with independent data, ports and cookies.
 foreach($mode in @('portable','dev')){
  Run "$fixture\server\initialize-server.ps1" @('-Mode',$mode,'-DatabaseOnly') ($mode+'-initialize')
  Run "$root\launcher\test-upgrade-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root) 'upgrade-database'
 Run "$root\launcher\seed-server-test.ps1" @('-Fixture',$fixture,'-Mode',$mode) ($mode+'-seed')
  $otherPort=Free-Port
  Run "$fixture\server\start-server.ps1" @('-Mode',$mode,'-Port',[string]$otherPort,'-NoBrowser') ($mode+'-start')
 }
 Run "$fixture\server\stop-server.ps1" @('-Mode','portable') 'portable-stop'
 if((Invoke-RestMethod "$base/health").status -ne 'UP'){throw 'Portable stop affected server'}
 $dev=Get-Content -LiteralPath "$fixture\data\dev\instance-upgrade.json" -Raw -Encoding UTF8|ConvertFrom-Json
 if((Invoke-RestMethod ("http://127.0.0.1:"+$dev.httpPort+"/health")).status -ne 'UP'){throw 'Portable stop affected developer main'}
 Run "$fixture\server\stop-server.ps1" @('-Mode','dev') 'dev-stop'
 Run "$fixture\server\stop-server.ps1" @() 'server-stop'
 $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$port);$listener.Start()
 try{Run "$fixture\server\start-server.ps1" $args 'port-conflict' 1}finally{$listener.Stop()}

 $network=(& "$fixture\server\check-network.ps1" -AsJson)|ConvertFrom-Json
 $physical=@($network|Where-Object {$_.hardware -and $_.status -eq 'Up'})
 if($physical.Count -eq 1){
  $ip=$physical[0].address;$lanPort=Free-Port
  Run "$fixture\server\start-server.ps1" @('-Address',$ip,'-Port',[string]$lanPort,'-NoBrowser') 'physical-ip-start'
  $request=[Net.HttpWebRequest]::Create("http://$($ip):$lanPort/health");$request.Proxy=$null;$request.Timeout=10000
  $response=$request.GetResponse();$reader=New-Object IO.StreamReader($response.GetResponseStream())
  try{if(($reader.ReadToEnd()|ConvertFrom-Json).status -ne 'UP'){throw 'Host physical IPv4 health failed'}}finally{$reader.Dispose();$response.Dispose()}
  Run "$fixture\server\stop-server.ps1" @() 'physical-ip-stop'
  $localIpResult='passed-host-only-not-cross-device'
 }
 # Move only a proven stopped, owned fixture; no external copies.
 $moved=Join-Path $run.path '移动 后目录'
 Assert-ArtifactPath $run.path $fixture|Out-Null;Assert-ArtifactPath $run.path $moved|Out-Null
 Move-Item -LiteralPath $fixture -Destination $moved;$fixture=$moved;Register-ArtifactInstance $run $fixture
 Run "$fixture\server\start-server.ps1" $args 'moved-restart'
 Run "$fixture\server\stop-server.ps1" @() 'moved-stop'
 Write-ArtifactJson (Join-Path $run.path 'acceptance-results.json') @{passed=$true;scope='local automation';runtimePath='Windows system paths only; Java/Maven/MySQL/Node excluded';physicalDevices='pending';hostPhysicalIPv4=$localIpResult;networkFirewall='not modified';modes=@('server','portable','dev');checks=@('empty-init','setup-repeat-denied','lifecycle','repeat-start','HTTP','browser','Excel','backup-restore','data-reuse','independent-modes','port-conflict','Unicode-space-path','move-restart','application-crash-recovery','database-crash-recovery')}
 $success=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 try{foreach($mode in @('server','portable','dev')){
  foreach($name in @('application','application-error','mysql-error','launcher')){
   $log=Join-Path $fixture ("logs/upgrade/"+$mode+"/"+$name+".log")
   if(Test-Path -LiteralPath $log){$text=Protect-ArtifactText (Read-ArtifactTail $log 131072);[IO.File]::WriteAllText((Join-Path $run.path ("logs/"+$mode+"-"+$name+".log")),$text,(New-Object Text.UTF8Encoding($false)))}
  }
 }
 }catch{Write-Warning ('Diagnostic log capture failed: '+$_.Exception.Message)}
 $env:PATH=$oldPath;$env:EMS_TEST_PASSWORD=$oldPassword;$env:EMS_BROWSER_AUTO_OPEN=$oldBrowser
 # Generic owned-run finalizer handles every registered mode and verifies all recorded processes.
 $exitCode=Complete-ArtifactRun $run -Succeeded:$success -OriginalError $failure
 Write-Host "Acceptance evidence: .release-evidence/1.2.0 or v1.2.0/$($run.id)"
 exit $exitCode
}
