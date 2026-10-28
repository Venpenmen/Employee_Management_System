param([ValidateSet('server','portable','dev')][string]$Mode='server',[string]$Address=$env:EMS_SERVER_ADDRESS,[int]$Port=0,[switch]$NoBrowser)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$lock=$null;$state=$null;$startedDb=$false;$startedApp=$false;$oldEnv=@{}
try{
 if($Port -eq 8083 -or $env:EMS_SERVER_PORT -eq '8083'){throw '8083 is reserved; v1.2.0 refuses to use it'}
 $lock=Enter-EmsLock;Assert-Package;$state=Read-EmsState
 Recover-EmsPendingSwitch $state
 if($state -and (Get-OwnedProcess $state.app)){
  if(-not(Test-EmsReady $state)){throw 'Recorded instance is unhealthy; stop it before retry'}
  Write-Host "Already running: http://$($state.address):$($state.httpPort)";exit 0
 }
 if(-not $state){$state=New-EmsState}
 if($Mode -ne 'server'){$Address='127.0.0.1'}
 if(-not $Port -and $Mode -eq 'server' -and $env:EMS_SERVER_PORT){$Port=[int]$env:EMS_SERVER_PORT}
 if(-not $Address){
  $config="$PSScriptRoot\server.json"
  if(Test-Path -LiteralPath $config){$v=Get-Content -LiteralPath $config -Raw -Encoding UTF8|ConvertFrom-Json;$Address=$v.address;if(-not $Port){$Port=$v.port}}
 }
 if(-not $Address){$Address='127.0.0.1'}
 $ip=$null
 if(-not [Net.IPAddress]::TryParse($Address,[ref]$ip) -or $ip.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $Address -eq '0.0.0.0'){throw 'Specify one local IPv4 address; wildcard binding is disabled'}
 if($Address -ne '127.0.0.1' -and -not(Get-NetIPAddress -AddressFamily IPv4|Where-Object IPAddress -eq $Address)){throw 'Configured address is not present on this host'}
 if(-not $Port){if($Mode -eq 'server' -and $env:EMS_SERVER_PORT){$Port=[int]$env:EMS_SERVER_PORT}else{$Port=@{server=8283;portable=8281;dev=8282}[$Mode]}}
 if($Port -in @(8083,8183)){throw 'v1.2.0 development requires loopback and never port8083'}
 if($Port -lt 1024 -or $Port -gt 65535){throw 'HTTP port must be between 1024 and 65535'}
 $listener=New-Object Net.Sockets.TcpListener($ip,$Port)
 try{$listener.Start()}catch{throw "HTTP address/port unavailable: $Address port $Port"}finally{$listener.Stop()}
 $state.address=$Address;$state.httpPort=$Port
 if((Test-Path -LiteralPath "$script:EmsRoot/data/mysql") -or (Test-Path -LiteralPath "$script:DataDir/mysql")){
  if(-not(Test-Path -LiteralPath "$script:DataDir/active-schema")){
   $lock.Dispose();$lock=$null
   $shell="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
   $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\upgrade-installation.ps1",'-LegacyRoot',$script:EmsRoot,'-Mode',$Mode) "$script:LogDir/upgrade.log"
   if($code -ne 0){throw 'Legacy upgrade did not complete; original data retained'}
   $lock=Enter-EmsLock;$state=Read-EmsState
   $state.address=$Address;$state.httpPort=$Port
   $script:DbName=[IO.File]::ReadAllText("$script:DataDir/active-schema").Trim()
   if($script:DbName -notmatch ('^ems_up_'+$Mode+'_v120_g[a-f0-9]{12}$')){throw 'Invalid upgraded database pointer'}
  }
 }
 Start-LocalDatabase $state;$startedDb=$true
 $cnf=Write-ClientConfig $state
 $accounts=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N',$script:DbName,'-e','SELECT COUNT(*) FROM user_account')
 if([int]$accounts -eq 0){Invoke-EmsFirstSetup $state}
 $executives=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N',$script:DbName,'-e','SELECT COUNT(*) FROM user_account a JOIN employee e ON e.id=a.employee_id WHERE a.account_status=1 AND e.employment_status=1 AND e.user_type=2')
 if([int]$executives -eq 0){throw '数据库已有记录但没有有效高层账号；不会重建或覆盖数据，请使用已验证的备份恢复'}
 $secret=Read-EmsSecrets
 Remove-Item -LiteralPath "$script:DataDir\stop.request","$script:DataDir\ready" -ErrorAction SilentlyContinue
 $vars=@{EMS_DATABASE_NAME=$script:DbName;EMS_DEV_DB_NAME=$script:DbName;EMS_UPGRADE_ROOT=$script:EmsRoot;EMS_UPGRADE_MODE=$Mode;EMS_DB_PORT=[string]$state.dbPort;EMS_DB_PASSWORD=$secret.app;EMS_HTTP_PORT=[string]$Port;EMS_SERVER_PORT=[string]$Port;EMS_SERVER_ADDRESS=$Address;EMS_INSTANCE_TOKEN=$secret.control;EMS_CONTROL_DIR=$script:DataDir;EMS_DEV_HTTP_PORT=[string]$Port;EMS_DEV_DB_PORT=[string]$state.dbPort;EMS_DEV_DB_PASSWORD=$secret.app}
 foreach($key in $vars.Keys){$oldEnv[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$vars[$key],'Process')}
 if($Mode -eq 'dev'){
  $cp="$script:EmsRoot\backend\target\dev-classpath.txt"
  if(-not(Test-Path -LiteralPath "$script:EmsRoot\backend\target\classes\com\example\ems\EmsApplication.class") -or -not(Test-Path -LiteralPath $cp)){throw 'Run Maven compile dependency:build-classpath -Dmdep.outputFile=target/dev-classpath.txt before development launch'}
  $classPath="$script:EmsRoot\backend\target\classes;"+([IO.File]::ReadAllText($cp).Trim())
  $args='-Dfile.encoding=UTF-8 -cp '+(Quote-EmsArgument $classPath)+' com.example.ems.EmsApplication'
 }else{$args='-Dfile.encoding=UTF-8 -jar '+(Quote-EmsArgument $script:JarFile)+' --spring.profiles.active='+$Mode}
 $p=Start-Process -FilePath $script:JavaExe -ArgumentList $args -WorkingDirectory $script:EmsRoot -WindowStyle Hidden -RedirectStandardOutput "$script:LogDir\application.log" -RedirectStandardError "$script:LogDir\application-error.log" -PassThru
 $state.app=Get-ProcessIdentity $p $script:JavaExe;$startedApp=$true;Save-EmsState $state
 for($i=0;$i -lt 120;$i++){
  if(-not(Get-OwnedProcess $state.app)){throw 'Application exited; inspect mode-specific application logs'}
  if(Test-EmsReady $state){Write-Host "EMS v1.2.0 $Mode ready: http://$($Address):$Port";if($Mode -eq 'portable' -and -not $NoBrowser){Start-Process "http://127.0.0.1:$Port"};exit 0}
  Start-Sleep -Seconds 1
 }
 throw 'Application readiness timed out'
}catch{
 Write-EmsLog ('START FAILED: '+$_.Exception.Message)
 $primary=$_.Exception.Message
 try{if($state){if($startedApp){Stop-EmsApplication $state};if($startedDb){Stop-EmsDatabase $state};Dismount-EmsNativeRoot $state;Save-EmsState $state}}catch{Write-EmsLog ('Cleanup incomplete: '+$_.Exception.Message)}
 Write-Error $primary -ErrorAction Continue;exit 1
}finally{
 foreach($key in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($key,$oldEnv[$key],'Process')}
 Clear-ClientConfigs;if($lock){$lock.Dispose()}
}
