param([Parameter(Mandatory=$true)][string]$CandidateDirectory,[int]$Port=55901,[switch]$KeepScratch)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$candidate=Assert-ArtifactPath (Join-Path $root 'dist') ([IO.Path]::GetFullPath($CandidateDirectory))
$extracted=Join-Path $candidate 'EMS_v1.2.1_CompleteCandidateRC2_服务器版'
if(-not(Test-Path -LiteralPath $extracted)){throw 'Extracted candidate runtime directory missing'}
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('rc2-smoke-'+[guid]::NewGuid().ToString('N').Substring(0,8))
$pwshExe=(Get-Process -Id $PID).Path
$result=@{passed=$false;checks=0;port=$Port;scratch=$scratch;steps=@();healthStatus=$null;loginStatus=$null;firstRunSetup=$false;cleanup='not-started'}
function Step([string]$Name,[bool]$Passed,[string]$Detail){
 $script:result.steps+=@{step=$Name;passed=$Passed;detail=$Detail}
 if($Passed){$script:result.checks++}else{throw ('Smoke step failed: '+$Name+' - '+$Detail)}
}
$ready=Join-Path $scratch 'data/complete-rc2/server/ready'
try{
 Copy-Item -LiteralPath $extracted -Destination $scratch -Recurse -Force
 Step 'extract-independent-candidate' $true $scratch
 $networkFile=Join-Path $scratch 'server-complete-rc2/network-mode.json'
 if(Test-Path -LiteralPath $networkFile){Remove-Item -LiteralPath $networkFile -Force}
 Step 'default-safe-mode-without-network-config' $true 'network-mode.json absent: default safe mode with one-time local entry code'

 $initOut=Join-Path $scratch 'init.out';$initErr=Join-Path $scratch 'init.err'
 $init=Start-Process $pwshExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $scratch 'server-complete-rc2/initialize-server.ps1'),'-DatabaseOnly') -WorkingDirectory $scratch -RedirectStandardOutput $initOut -RedirectStandardError $initErr -PassThru -WindowStyle Hidden
 if(-not $init.WaitForExit(300000)){throw 'Database initialization timed out'}
 if($init.ExitCode -ne 0){throw ('Database initialization failed: '+(Get-Content $initErr -Raw -ErrorAction SilentlyContinue))}
 Step 'initialize-isolated-database' $true ('schema initialized from candidate bootstrap; log bytes='+(Get-Item $initOut).Length)

 $seedScript=Join-Path $scratch 'smoke-seed.ps1'
 $seedBody=@'
$ErrorActionPreference='Stop'
. "$PSScriptRoot\server-complete-rc2\runtime-common.ps1" -Mode server
$lock=Enter-EmsLock
try{
 $state=Read-EmsState
 Start-LocalDatabase $state
 $null=Write-ClientConfig $state
 $previous=@{}
 $values=[ordered]@{EMS_SETUP_NO='900001';EMS_SETUP_NAME='冒烟验证';EMS_SETUP_ID='11010519491231002X';EMS_SETUP_PHONE='13800000000';EMS_SETUP_BIRTH='1949-12-31';EMS_SETUP_HIRE='2026-10-30';EMS_SETUP_PASSWORD='Smoke1234';EMS_SETUP_URL=("jdbc:mysql://127.0.0.1:"+$state.dbPort+"/"+$script:DbName+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");EMS_DB_PASSWORD=(Read-EmsSecrets).app}
 foreach($key in $values.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
 try{Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-Dloader.main=com.example.ems.tools.LocalSetup','-cp',$script:JarFile,'org.springframework.boot.loader.launch.PropertiesLauncher')|Write-Host}
 finally{foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')};$values.Clear()}
 Save-EmsState $state
 Write-Host 'SEED OK'
}finally{Clear-ClientConfigs;if($lock){$lock.Dispose()}}
'@
 [IO.File]::WriteAllText($seedScript,$seedBody,(New-Object Text.UTF8Encoding($false)))
 $seedOut=Join-Path $scratch 'seed.out';$seedErr=Join-Path $scratch 'seed.err'
 $seed=Start-Process $pwshExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$seedScript) -WorkingDirectory $scratch -RedirectStandardOutput $seedOut -RedirectStandardError $seedErr -PassThru -WindowStyle Hidden
 if(-not $seed.WaitForExit(300000)){throw 'Candidate account seeding timed out'}
 $seedText=Get-Content $seedOut -Raw -Encoding UTF8
 if($seed.ExitCode -ne 0 -or $seedText -notmatch 'SEED OK'){throw ('Candidate account seeding failed: '+$seedText+(Get-Content $seedErr -Raw -ErrorAction SilentlyContinue))}
 Step 'candidate-first-setup-tool' $true 'LocalSetup created the first executive account inside the extracted candidate database'

 $startOut=Join-Path $scratch 'start.out';$startErr=Join-Path $scratch 'start.err'
 $start=Start-Process $pwshExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $scratch 'server-complete-rc2/start-server.ps1'),'-NoBrowser','-Port',[string]$Port) -WorkingDirectory $scratch -RedirectStandardOutput $startOut -RedirectStandardError $startErr -PassThru -WindowStyle Hidden
 if(-not $start.WaitForExit(300000)){throw 'Candidate start timed out'}
 if($start.ExitCode -ne 0){throw ('Candidate start failed: '+(Get-Content $startErr -Raw -ErrorAction SilentlyContinue)+(Get-Content $startOut -Raw -ErrorAction SilentlyContinue))}
 $startText=Get-Content $startOut -Raw -Encoding UTF8
 if($startText -notmatch 'ready: http://127\.0\.0\.1:'+$Port){throw 'Start output did not report loopback readiness'}
 $result.firstRunSetup=$true
 if(-not(Test-Path -LiteralPath $ready)){throw 'Readiness marker missing in candidate data directory'}
 Step 'start-from-independent-directory' $true ('ready marker present; loopback port='+$Port)

 $health=$null
 for($i=0;$i -lt 30;$i++){
  try{$health=(Invoke-WebRequest -Uri ('http://127.0.0.1:'+$Port+'/health') -UseBasicParsing -TimeoutSec 5);break}catch{if($_.Exception.Response){$health=$_.Exception.Response;break};Start-Sleep -Seconds 1}
 }
 if(-not $health){throw 'HTTP health endpoint unreachable'}
 $result.healthStatus=[int]$health.StatusCode
 if($result.healthStatus -ge 500){throw ('Health endpoint returned '+$result.healthStatus)}
 Step 'http-health' $true ('status='+$result.healthStatus)

 $login=$null
 try{$login=(Invoke-WebRequest -Uri ('http://127.0.0.1:'+$Port+'/login') -UseBasicParsing -TimeoutSec 10)}catch{if($_.Exception.Response){$login=$_.Exception.Response}else{throw ('Login page unreachable: '+$_.Exception.Message)}}
 $result.loginStatus=[int]$login.StatusCode
 if($result.loginStatus -ne 200){throw ('Login page returned '+$result.loginStatus)}
 Step 'http-login-page' $true ('status=200 contentLength='+$login.RawContentLength)

 $checkOut=Join-Path $scratch 'check.out'
 $check=Start-Process $pwshExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $scratch 'server-complete-rc2/check-server.ps1')) -WorkingDirectory $scratch -RedirectStandardOutput $checkOut -RedirectStandardError (Join-Path $scratch 'check.err') -PassThru -WindowStyle Hidden
 $null=$check.WaitForExit(120000)
 $checkText=Get-Content $checkOut -Raw -Encoding UTF8
 if($checkText -notmatch 'ready\s*:\s*True' -or $checkText -notmatch 'applicationRunning\s*:\s*True' -or $checkText -notmatch 'databaseRunning\s*:\s*True'){throw ('check-server did not report a healthy instance: '+$checkText)}
 Step 'check-server-healthy' $true 'application, database and readiness all true'

 $stopOut=Join-Path $scratch 'stop.out'
 $stop=Start-Process $pwshExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $scratch 'server-complete-rc2/stop-server.ps1')) -WorkingDirectory $scratch -RedirectStandardOutput $stopOut -RedirectStandardError (Join-Path $scratch 'stop.err') -PassThru -WindowStyle Hidden
 $null=$stop.WaitForExit(180000)
 if($stop.ExitCode -ne 0){throw ('Candidate stop failed: '+(Get-Content (Join-Path $scratch 'stop.err') -Raw -ErrorAction SilentlyContinue))}
 if(Test-Path -LiteralPath $ready){throw 'Readiness marker still present after stop'}
 Step 'stop-and-clean-state' $true 'stop-server removed readiness marker and reported clean stop'
 $result.passed=$true
 $result.cleanup='removing-scratch'
}catch{
 $result.error=$_.Exception.Message
 $result.logTail=@()
 foreach($name in @('init.err','seed.err','start.err','start.out','check.err','stop.err')){
  $file=Join-Path $scratch $name
  if(Test-Path -LiteralPath $file){$result.logTail+=($name+': '+(Get-Content $file -Raw -ErrorAction SilentlyContinue))}
 }
 try{
  $stopScript=Join-Path $scratch 'server-complete-rc2/stop-server.ps1'
  if(Test-Path -LiteralPath $stopScript){& $pwshExe -NoProfile -ExecutionPolicy Bypass -File $stopScript *> $null}
 }catch{}
}finally{
 if(-not $KeepScratch -and $result.passed){try{Remove-Item -LiteralPath $scratch -Recurse -Force;$result.cleanup='removed'}catch{$result.cleanup=('retained: '+$_.Exception.Message)}}
 elseif($KeepScratch){$result.cleanup='kept-by-request'}
 else{$result.cleanup='retained for inspection'}
 $result.scratch=$scratch
 Write-ArtifactJson (Join-Path $candidate 'CANDIDATE_SMOKE.json') $result
 if(-not $result.passed){throw ('Candidate smoke verification failed: '+$result.error)}
}
Write-Host ('Candidate started from its own extracted directory, served loopback HTTP and stopped cleanly; checks='+$result.checks)
