param([string]$NodeExe='E:/nodejs/node.exe',[switch]$OfficialDemo)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$root/launcher/package-rules.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Owned continuation task required'}
if(-not(Test-Path -LiteralPath $NodeExe)){throw 'Local Node runtime required; no download attempted'}
if(([IO.DriveInfo]::new([IO.Path]::GetPathRoot($root))).AvailableFreeSpace -lt 2500MB){throw 'Insufficient isolated fixture space; existing files are unchanged'}
$run=New-ArtifactRun $root 'controlled-legacy-roundtrip'
$next=Join-Path $run.path '新版 独立测试';$legacy=Join-Path $run.path '受控旧版 独立测试'
$work=Join-Path $run.path 'roundtrip';$shell=Join-Path $PSHOME 'pwsh.exe'
$ok=$false;$failure='';$fixtures=@();$saved=@{}
foreach($key in @('EMS_IT_URL','EMS_IT_PASSWORD','EMS_TEST_PASSWORD','EMS_LEGACY_COOKIE','SERVER_SERVLET_SESSION_COOKIE_NAME','JAVA_TOOL_OPTIONS','EMS_DEMO_BUNDLE','EMS_CONTROLLED_ROOT')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$cp="$root/backend/target/test-classes;$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim()
function Invoke-OwnedJava([string]$Fixture,[string]$Phase){
 . "$Fixture/server/runtime-common.ps1"
 $state=Read-EmsState;Mount-EmsNativeRoot $state;$secret=Read-EmsSecrets
 $env:EMS_CONTROLLED_ROOT=$Fixture
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($state.dbPort)/$script:DbName`?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";$env:EMS_IT_PASSWORD=$secret.root
 $arguments=@('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.ControlledLegacyIT',$Phase,$work)
 if($Phase -eq 'install'){$arguments+=(Join-Path $legacy 'app/ems-1.2.0-rc1.jar')}
 $exitCode=Invoke-ArtifactNative $script:JavaExe $arguments "$($run.path)/logs/$Phase.log"
 if($exitCode -ne 0){throw "Controlled phase $Phase failed; inspect its safe test log"}
}
function Stop-OwnedFixture([string]$Fixture){
 if(Test-Path -LiteralPath "$Fixture/data/server/instance-upgrade.json"){
  . "$Fixture/server/runtime-common.ps1"
  $state=Read-EmsState;if($state){Mount-EmsNativeRoot $state;Stop-EmsApplication $state;Stop-EmsDatabase $state;Dismount-EmsNativeRoot $state;Save-EmsState $state};Clear-ClientConfigs
 }
}
try{
 # Scope a logging configuration to these child JVMs; do not log JDBC URLs or secrets.
 $logging=Join-Path $run.path 'test-logback.xml';[IO.File]::WriteAllText($logging,'<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>')
 $env:JAVA_TOOL_OPTIONS=$saved.JAVA_TOOL_OPTIONS+' -Dlogback.configurationFile="'+$logging+'"'
 $env:EMS_DEMO_BUNDLE=if($OfficialDemo){Join-Path (Split-Path $root) 'employee-management-system_v1.2.0/dist/EMS_v1.2.0_DemoData.zip'}else{$null}
 [IO.Directory]::CreateDirectory($next)|Out-Null
 foreach($file in Get-RcFiles $root 'runtime'){$destination=Join-Path $next $file.FullName.Substring($root.Length+1);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))|Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $destination}
 $fixtures+=$next;Register-ArtifactInstance $run $next
 $oldZip=Join-Path (Split-Path $root) 'employee-management-system_v1.2.0/dist/EMS_v1.2.0_Windows_Portable.zip'
 $expected=([IO.File]::ReadAllText($oldZip+'.sha256').Trim() -split '\s+')[0]
 if((Get-FileHash -LiteralPath $oldZip -Algorithm SHA256).Hash -ne $expected){throw 'Immutable historical runtime ZIP hash mismatch'}
 [IO.Compression.ZipFile]::ExtractToDirectory($oldZip,$legacy)
 $null=Assert-ArtifactPath $run.path $legacy;$null=@(Get-ArtifactFiles $legacy -Strict)
 $fixtures+=$legacy;Register-ArtifactInstance $run $legacy
 foreach($fixture in $fixtures){$code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$fixture/server/initialize-server.ps1",'-DatabaseOnly') "$($run.path)/logs/initialize-$([IO.Path]::GetFileName($fixture)).log";if($code -ne 0){throw 'Fresh isolated database initialization failed'}}
 Invoke-OwnedJava $next 'source'
 Invoke-OwnedJava $legacy 'install'
 $env:EMS_LEGACY_COOKIE='EMS120_P1_'+[guid]::NewGuid().ToString('N');$env:SERVER_SERVLET_SESSION_COOKIE_NAME=$env:EMS_LEGACY_COOKIE
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-File',"$legacy/server/start-server.ps1",'-Address','127.0.0.1','-Port','8485','-NoBrowser') "$($run.path)/logs/old-start.log";if($code -ne 0){throw 'Actual old server did not start'}
 $env:EMS_TEST_PASSWORD='SyntheticPassword123'
 $code=Invoke-ArtifactNative $NodeExe @("$root/tools/test-old-roundtrip.cjs",'http://127.0.0.1:8485',"$($run.path)/old-http-results.json","$work/test-auth.emsbk") "$($run.path)/logs/old-http.log";if($code -ne 0){throw 'Actual old server HTTP regression failed'}
 Invoke-OwnedJava $legacy 'return'
 Invoke-OwnedJava $next 'restore'
 Copy-Item -LiteralPath "$work/controlled-roundtrip-results.json" -Destination "$($run.path)/controlled-roundtrip-results.json"
 if($OfficialDemo){Copy-Item -LiteralPath "$work/demo-schema-results.json" -Destination "$($run.path)/demo-schema-results.json"}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 foreach($fixture in $fixtures){try{Stop-OwnedFixture $fixture}catch{$ok=$false;$failure+='; cleanup retained: '+$_.Exception.Message}}
 foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
 # Reload the current scoped artifact manager after using helpers from the old copy.
 . "$root/launcher/artifact-common.ps1"
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure
 exit $code
}
