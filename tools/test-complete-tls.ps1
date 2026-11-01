param([string]$NodeExe='E:/nodejs/node.exe',[string]$KeytoolExe='C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot/bin/keytool.exe')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-tls'
$fixture=Join-Path $run.path '原生 TLS 隔离代理'
$ok=$false;$failure='';$saved=@{}
foreach($name in @('EMS_TLS_STORE_PASSWORD','EMS_TEST_PASSWORD')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$shell=Join-Path $PSHOME 'pwsh.exe'
function Run-Script([string]$File,$Arguments,[string]$Label){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-File',$File)+$Arguments) "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
try{
 if(-not(Test-Path -LiteralPath $KeytoolExe -PathType Leaf)){throw 'Existing local JDK keytool required; no downloads'}
 Copy-CompleteFixture $run $fixture
 $env:EMS_TLS_STORE_PASSWORD='Tls'+[guid]::NewGuid().ToString('N')+'9';$env:EMS_TEST_PASSWORD='Synthetic'+[guid]::NewGuid().ToString('N')+'9'
 $tls=Join-Path $fixture 'data/complete/server/tls';[IO.Directory]::CreateDirectory($tls)|Out-Null
 $code=Invoke-ArtifactNative $KeytoolExe @('-genkeypair','-alias','owned-tls','-keyalg','RSA','-keysize','2048','-storetype','PKCS12','-keystore',"$tls/backend.p12",'-storepass:env','EMS_TLS_STORE_PASSWORD','-keypass:env','EMS_TLS_STORE_PASSWORD','-dname','CN=localhost','-ext','SAN=dns:localhost,ip:127.0.0.1','-validity','1','-noprompt') "$($run.path)/logs/keytool-generate.log";if($code -ne 0){throw 'Local test certificate generation failed'}
 $code=Invoke-ArtifactNative $KeytoolExe @('-exportcert','-rfc','-alias','owned-tls','-keystore',"$tls/backend.p12",'-storepass:env','EMS_TLS_STORE_PASSWORD','-file',"$tls/anchor.pem") "$($run.path)/logs/keytool-export.log";if($code -ne 0){throw 'Local test anchor export failed'}
 Write-ArtifactJson "$fixture/server-complete/network-mode.json" @{mode='proxy';publicOrigin='https://localhost:8484';keyStore='data/complete/server/tls/backend.p12';trustCertificate='data/complete/server/tls/anchor.pem'}
 Run-Script "$fixture/server-complete/initialize-server.ps1" @('-DatabaseOnly') 'initialize'
 Run-Script "$root/launcher/seed-server-test.ps1" @('-Fixture',$fixture,'-RuntimeDirectory','server-complete') 'seed'
 Run-Script "$fixture/server-complete/start-server.ps1" @('-NoBrowser') 'start-tls'
 $code=Invoke-ArtifactNative $NodeExe @("$PSScriptRoot/test-complete-tls.cjs",$fixture,"$($run.path)/tls-results.json",'tls') "$($run.path)/logs/tls-http.log";if($code -ne 0){throw 'Native TLS and isolated reverse proxy checks failed'}
 Run-Script "$fixture/server-complete/stop-server.ps1" @() 'stop-tls'
 Write-ArtifactJson "$fixture/server-complete/network-mode.json" @{mode='compatibility'}
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$fixture/server-complete/start-server.ps1",'-NoBrowser') "$($run.path)/logs/invalid-mode.log";if($code -eq 0){throw 'Invalid mode started instead of refusing'}
 Write-ArtifactJson "$fixture/server-complete/network-mode.json" @{mode='local'}
 Run-Script "$fixture/server-complete/start-server.ps1" @('-NoBrowser') 'restart-local'
 $code=Invoke-ArtifactNative $NodeExe @("$PSScriptRoot/test-complete-tls.cjs",$fixture,"$($run.path)/mode-restart-results.json",'local') "$($run.path)/logs/local-http.log";if($code -ne 0){throw 'Mode restart did not preserve account/database'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 try{foreach($file in Get-ChildItem "$fixture/logs/complete/server" -Filter '*.log' -File -ErrorAction SilentlyContinue){[IO.File]::WriteAllText("$($run.path)/logs/application-$($file.Name)",(Protect-ArtifactText (Read-ArtifactTail $file.FullName 32768)))}}catch{}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
