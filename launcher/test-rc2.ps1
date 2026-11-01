param([string]$NodeExe,[string]$BrowserExe='C:\Program Files\Google\Chrome\Application\chrome.exe',[switch]$KeepArtifacts,[switch]$SkipHistorical,[switch]$MigrationOnly)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$run=New-ArtifactRun $root 'rc-two-security' -KeepArtifacts:$KeepArtifacts;$fixture=Join-Path $run.path '安全升级 验收';$ok=$false;$failure='';$oldPassword=$env:EMS_TEST_PASSWORD;$oldBrowser=$env:EMS_BROWSER_AUTO_OPEN
$shell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"
function Run($Script,$Arguments,$Name){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$Script)+$Arguments) (Join-Path $run.path ('logs/'+$Name+'.log'));if($code -ne 0){throw "$Name failed: $code"}}
try{
 $NodeExe=Resolve-ArtifactTool $NodeExe 'node.exe';New-Item -ItemType Directory -Path $fixture -Force|Out-Null
 foreach($name in @('runtime','app','bootstrap','server','launcher')){Copy-Item -LiteralPath "$root/$name" -Destination "$fixture/$name" -Recurse}
 New-Item -ItemType Directory -Path "$fixture/backend/target/classes" -Force|Out-Null;Copy-Item -Path "$root/backend/target/classes/*" -Destination "$fixture/backend/target/classes" -Recurse;Copy-Item "$root/backend/target/dev-classpath.txt" "$fixture/backend/target/dev-classpath.txt"
 Write-ArtifactJson "$fixture/test-fixture.json" @{run=$run.id;version='1.2.1';build='rc2'};Register-ArtifactInstance $run $fixture
 $env:EMS_TEST_PASSWORD='Qa'+[Guid]::NewGuid().ToString('N')+'7';$env:EMS_BROWSER_AUTO_OPEN='false'
 Run "$fixture/server/initialize-server.ps1" @('-DatabaseOnly') 'initialize-v3'
 if(-not $SkipHistorical){Run "$root/launcher/test-upgrade-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root) 'historical-upgrade'}
 Run "$root/launcher/test-rc2-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root) 'rc2-migration'
 Run "$root/launcher/seed-server-test.ps1" @('-Fixture',$fixture) 'seed'
 Run "$root/launcher/test-rc2-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root,'-SecurityOnly') 'rc2-transactions'
 Run "$fixture/server/initialize-server.ps1" @('-Mode','portable','-DatabaseOnly') 'portable-migration-initialize'
 Run "$root/launcher/test-rc2-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root,'-Mode','portable') 'portable-rc2-migration'
 Run "$fixture/server/stop-server.ps1" @('-Mode','portable') 'portable-migration-stop'
 if($MigrationOnly){Write-ArtifactJson (Join-Path $run.path 'migration-only-results.json') @{passed=$true;scope='server+portable migration and transactional database checks; HTTP/browser not rerun'};$ok=$true;return}
 $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);$listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop()
 Run "$fixture/server/start-server.ps1" @('-Address','127.0.0.1','-Port',[string]$port,'-NoBrowser') 'start'
 $code=Invoke-ArtifactNative $NodeExe @("$root/launcher/test-rc2-http.cjs","http://127.0.0.1:$port",(Join-Path $run.path 'rc2-http-results.json')) (Join-Path $run.path 'logs/rc2-http.log');if($code -ne 0){throw 'rc2 HTTP security failed'}
 $code=Invoke-ArtifactNative $NodeExe @("$root/launcher/test-rc2-browser.cjs","http://127.0.0.1:$port",(Join-Path $run.path 'rc2-browser-results.json'),$BrowserExe,(Join-Path $run.path 'browser-profile')) (Join-Path $run.path 'logs/rc2-browser.log');if($code -ne 0){throw 'rc2 browser security failed'}
 Run "$fixture/server/stop-server.ps1" @() 'stop' 
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{try{foreach($name in @('application','application-error','launcher','mysql-error')){$log=Join-Path $fixture ("logs/upgrade/server/"+$name+".log");if(Test-Path -LiteralPath $log){[IO.File]::WriteAllText((Join-Path $run.path ("logs/server-"+$name+".log")),(Protect-ArtifactText (Read-ArtifactTail $log 131072)),(New-Object Text.UTF8Encoding($false)))}}}catch{Write-Warning 'Diagnostic capture incomplete'};$env:EMS_TEST_PASSWORD=$oldPassword;$env:EMS_BROWSER_AUTO_OPEN=$oldBrowser;$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
