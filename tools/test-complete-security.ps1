param([string]$NodeExe='E:/nodejs/node.exe',[string]$BrowserExe='C:/Program Files/Google/Chrome/Application/chrome.exe')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-account-security';$fixture=Join-Path $run.path '账户安全 完整候选';$ok=$false;$failure='';$saved=@{};$shell=Join-Path $PSHOME 'pwsh.exe'
foreach($name in @('EMS_TEST_PASSWORD','EMS_TEST_COOKIE_NAME','EMS_BROWSER_AUTO_OPEN')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Run-Script([string]$File,$Arguments,[string]$Label){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-File',$File)+$Arguments) "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
try{
 if(-not(Test-Path -LiteralPath $BrowserExe -PathType Leaf)){throw 'Actual local Chrome required; no downloads'}
 Copy-CompleteFixture $run $fixture
 $env:EMS_TEST_PASSWORD='Synthetic'+[guid]::NewGuid().ToString('N')+'7';$env:EMS_TEST_COOKIE_NAME='EMS121N_COMPLETE_SERVER';$env:EMS_BROWSER_AUTO_OPEN='false'
 Run-Script "$fixture/server-complete/initialize-server.ps1" @('-DatabaseOnly') 'initialize'
 Run-Script "$root/launcher/seed-server-test.ps1" @('-Fixture',$fixture,'-RuntimeDirectory','server-complete') 'seed'
 Run-Script "$root/launcher/test-rc2-database.ps1" @('-Fixture',$fixture,'-ProjectRoot',$root,'-RuntimeDirectory','server-complete','-SecurityOnly') 'security-transactions'
 Run-Script "$fixture/server-complete/start-server.ps1" @('-NoBrowser') 'start'
 $code=Invoke-ArtifactNative $NodeExe @("$root/launcher/test-rc2-http.cjs",'http://127.0.0.1:8483',"$($run.path)/security-http-results.json") "$($run.path)/logs/security-http.log";if($code -ne 0){throw 'Existing security HTTP regression failed'}
 $code=Invoke-ArtifactNative $NodeExe @("$root/launcher/test-rc2-browser.cjs",'http://127.0.0.1:8483',"$($run.path)/security-browser-results.json",$BrowserExe,"$($run.path)/browser-profile") "$($run.path)/logs/security-browser.log";if($code -ne 0){throw 'Existing security browser regression failed'}
 Write-ArtifactJson "$($run.path)/security-complete-results.json" @{passed=$true;transactionalDatabase=$true;http=$true;actualChrome=$true;ownedCompleteNamespace=$true;scope='synthetic three-role account security; no production or real second-device test'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 try{foreach($file in Get-ChildItem "$fixture/logs/complete/server" -File -Filter '*.log' -ErrorAction SilentlyContinue){[IO.File]::WriteAllText("$($run.path)/logs/application-$($file.Name)",(Protect-ArtifactText (Read-ArtifactTail $file.FullName 32768)))}}catch{}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
