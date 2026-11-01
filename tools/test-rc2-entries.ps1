param([string]$NodeExe='E:/nodejs/node.exe',[string]$KeytoolExe='C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot/bin/keytool.exe',[switch]$BoundaryOnly)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/rc2-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$run=New-ArtifactRun $root 'complete-native-entries' -EvidenceGroup complete-rc2;$fixture=Join-Path $run.path 'RC2 双入口 隔离';$ok=$false;$failure='';$saved=@{};$shell=Join-Path $PSHOME 'pwsh.exe'
foreach($name in @('EMS_TLS_STORE_PASSWORD','EMS_TEST_PASSWORD','EMS_TEST_PWSH','LOGGING_LEVEL_COM_EXAMPLE_EMS_NETWORK')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Step([string]$File,$Arguments,[string]$Label){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-File',$File)+$Arguments) "$($run.path)/logs/$Label.log";if($code -ne 0){throw "$Label failed"}}
function Free-Port {$l=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);try{$l.Start();return $l.LocalEndpoint.Port}finally{$l.Stop()}}
try{
 Copy-Rc2Fixture $run $fixture
 $env:EMS_TLS_STORE_PASSWORD='SyntheticTLS'+[guid]::NewGuid().ToString('N')+'9';$env:EMS_TEST_PASSWORD='SyntheticLogin'+[guid]::NewGuid().ToString('N')+'9';$env:EMS_TEST_PWSH=$shell;$env:LOGGING_LEVEL_COM_EXAMPLE_EMS_NETWORK='DEBUG'
 $tls=Join-Path $fixture 'data/complete-rc2/server/tls';[IO.Directory]::CreateDirectory($tls)|Out-Null
 $code=Invoke-ArtifactNative $KeytoolExe @('-genkeypair','-alias','owned-tls','-keyalg','RSA','-keysize','2048','-storetype','PKCS12','-keystore',"$tls/backend.p12",'-storepass:env','EMS_TLS_STORE_PASSWORD','-keypass:env','EMS_TLS_STORE_PASSWORD','-dname','CN=ems-test.invalid','-ext','SAN=dns:ems-test.invalid','-validity','1','-noprompt') "$($run.path)/logs/keytool-generate.log";if($code -ne 0){throw 'Owned test certificate generation failed'}
 $code=Invoke-ArtifactNative $KeytoolExe @('-exportcert','-rfc','-alias','owned-tls','-keystore',"$tls/backend.p12",'-storepass:env','EMS_TLS_STORE_PASSWORD','-file',"$tls/anchor.pem") "$($run.path)/logs/keytool-export.log";if($code -ne 0){throw 'Owned trust anchor export failed'}
 $http=Free-Port;$https=Free-Port;while($https -eq $http){$https=Free-Port}
 Write-ArtifactJson "$fixture/server-complete-rc2/network-mode.json" @{format=1;mode='safe';generation=[guid]::NewGuid().ToString();httpsEnabled=$true;httpsPort=$https;secureOrigin='https://ems-test.invalid';keyStore='data/complete-rc2/server/tls/backend.p12';trustCertificate='data/complete-rc2/server/tls/anchor.pem'}
 Step "$fixture/server-complete-rc2/initialize-server.ps1" @('-DatabaseOnly') 'initialize'
 Step "$root/launcher/seed-server-test.ps1" @('-Fixture',$fixture,'-RuntimeDirectory','server-complete-rc2') 'seed'
 Step "$fixture/server-complete-rc2/start-server.ps1" @('-Port',[string]$http,'-NoBrowser') 'start'
 $nodeScript=if($BoundaryOnly){"$PSScriptRoot/test-rc2-boundary.cjs"}else{"$PSScriptRoot/test-rc2-entries.cjs"}
 $code=Invoke-ArtifactNative $NodeExe @($nodeScript,$fixture,"$($run.path)/rc2-entry-results.json") "$($run.path)/logs/entries.log";if($code -ne 0){throw 'Actual RC2 entry checks failed'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 try{foreach($file in Get-ChildItem "$fixture/logs/complete-rc2/server" -Filter '*.log' -File -ErrorAction SilentlyContinue){[IO.File]::WriteAllText("$($run.path)/logs/application-$($file.Name)",(Protect-ArtifactText (Read-ArtifactTail $file.FullName 65536)))}}catch{$failure+='; diagnostic capture incomplete'}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
