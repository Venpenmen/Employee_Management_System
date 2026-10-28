param([ValidateSet('server','portable','dev')][string]$Mode='server')
# Windows PowerShell 5.1 compatible; no globally installed tools are used.
$script:EmsRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$script:DataDir = Join-Path $script:EmsRoot ('data\'+$Mode)
$script:LogDir = Join-Path $script:EmsRoot ('logs\upgrade\'+$Mode)
$script:JavaExe = Join-Path $script:EmsRoot 'runtime\java\bin\java.exe'
$script:MysqlHome = Join-Path $script:EmsRoot 'runtime\mysql'
$script:MysqlExe = Join-Path $script:MysqlHome 'bin\mysql.exe'
$script:MysqldExe = Join-Path $script:MysqlHome 'bin\mysqld.exe'
$script:JarFile = Join-Path $script:EmsRoot 'app\ems-1.2.0-rc1.jar'
$script:Utf8 = New-Object Text.UTF8Encoding($false)
$script:NativeRoot = $null
if (-not ('Ems.DosDevice' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
namespace Ems {
    public static class DosDevice {
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        public static extern uint QueryDosDevice(string name, StringBuilder target, int length);
    }
}
'@
}


$script:Mode=$Mode
$script:DbName=@{server='ems_up_server_v120';portable='ems_up_portable_v120';dev='ems_up_dev_v120'}[$Mode]
if(Test-Path -LiteralPath (Join-Path $script:DataDir 'active-schema')){
 $candidate=[IO.File]::ReadAllText((Join-Path $script:DataDir 'active-schema')).Trim()
 if($candidate -notmatch ('^ems_up_'+$Mode+'_v120(_g[a-z0-9]{12})?$')){throw 'Invalid active database pointer'}
 $script:DbName=$candidate
}
. "$script:EmsRoot\launcher\artifact-common.ps1"
Assert-ArtifactPath $script:EmsRoot $script:DataDir | Out-Null
Assert-ArtifactPath $script:EmsRoot $script:LogDir|Out-Null
New-Item -ItemType Directory -Path $script:DataDir,$script:LogDir -Force|Out-Null
$acl=New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User,(New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))){
 $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
 $acl.AddAccessRule($rule)
}
if(([IO.DirectoryInfo]$script:DataDir).PSObject.Methods.Name -contains 'SetAccessControl'){([IO.DirectoryInfo]$script:DataDir).SetAccessControl($acl)}
else{[IO.FileSystemAclExtensions]::SetAccessControl(([IO.DirectoryInfo]$script:DataDir),$acl)}
function Get-EmsDriveTarget([string]$Drive) {
    $buffer = New-Object Text.StringBuilder 32768
    if ([Ems.DosDevice]::QueryDosDevice($Drive, $buffer, $buffer.Capacity) -eq 0) { return '' }
    return $buffer.ToString().TrimEnd([char]0)
}
function Mount-EmsNativeRoot($State) {
    $target = '\??\' + $script:EmsRoot
    if ($State.nativeDrive) {
        $actual = Get-EmsDriveTarget $State.nativeDrive
        if ($actual -and -not [string]::Equals($actual.TrimEnd('\'), $target.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) {
            # After a stopped folder has moved, choose a fresh mapping; never remove another mapping.
            if ((Get-OwnedProcess $State.database)) { throw 'The database drive mapping now belongs to another directory. Stop the original instance before moving it.' }
            $State.nativeDrive = ''
        } elseif ($actual) { $script:NativeRoot = $State.nativeDrive + '\'; return }
    }
    $candidates = if ($State.nativeDrive) { @($State.nativeDrive) } else { @([int][char]'Z'..[int][char]'G' | ForEach-Object { ([char]$_).ToString() + ':' }) }
    foreach ($drive in $candidates) {
        if (Get-EmsDriveTarget $drive) { continue }
        $null = & "$env:SystemRoot\System32\subst.exe" $drive $script:EmsRoot
        if ($LASTEXITCODE -eq 0 -and (Get-EmsDriveTarget $drive) -eq $target) {
            $State.nativeDrive = $drive
            $script:NativeRoot = $drive + '\'
            Write-EmsLog "Temporary local drive $drive assigned to this package for MySQL Unicode-path compatibility."
            return
        }
    }
    throw 'No free temporary drive letter (G:-Z:) is available. Free one drive letter and retry.'
}
function Dismount-EmsNativeRoot($State) {
    if (-not $State.nativeDrive) { return }
    if ((Get-OwnedProcess $State.app) -or (Get-OwnedProcess $State.database)) { return }
    $target = '\??\' + $script:EmsRoot
    if ((Get-EmsDriveTarget $State.nativeDrive) -eq $target) {
        $null = & "$env:SystemRoot\System32\subst.exe" $State.nativeDrive /D
        if ($LASTEXITCODE -ne 0) { throw 'Could not remove the temporary drive mapping.' }
    }
    $State.nativeDrive = ''
    $script:NativeRoot = $null
}
function Get-NativePath([string]$Path) {
    if (-not $script:NativeRoot) { return $Path }
    return $Path.Replace($script:EmsRoot + '\', $script:NativeRoot).Replace($script:EmsRoot + '/', $script:NativeRoot)
}

function Write-Utf8([string]$Path, [string]$Value) { Assert-ArtifactPath $script:EmsRoot $Path|Out-Null;[IO.File]::WriteAllText($Path, $Value, $script:Utf8) }
function Write-EmsLog([string]$Message) {
    [IO.Directory]::CreateDirectory($script:LogDir) | Out-Null
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    [IO.File]::AppendAllText("$script:LogDir\launcher.log", "$line`r`n", $script:Utf8)
    Write-Host $line
}
function Enter-EmsLock {
    [IO.Directory]::CreateDirectory($script:DataDir) | Out-Null
    [IO.Directory]::CreateDirectory($script:LogDir) | Out-Null
    try { return [IO.File]::Open("$script:DataDir\launcher.lock", 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another start/stop operation is in progress, or this folder is not writable. Wait and retry; extract the ZIP before starting.' }
}
function Quote-EmsArgument([string]$Value) {
    if ($Value.Contains('"') -or $Value.EndsWith('\')) { throw 'Unsupported executable argument.' }
    return '"' + $Value + '"'
}
function Invoke-EmsTool {
    param([string]$Exe, [string[]]$Arguments, [string]$InputText = '', [string]$InputFile = '', [int]$TimeoutSeconds = 180)
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Exe
    $info.Arguments = (($Arguments | ForEach-Object { Quote-EmsArgument (Get-NativePath $_) }) -join ' ')
    $info.WorkingDirectory = if ($script:NativeRoot) { $script:NativeRoot } else { $script:EmsRoot }
    $info.FileName = Get-NativePath $Exe
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $script:Utf8
    $info.StandardErrorEncoding = $script:Utf8
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw "Cannot start $Exe" }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $writer = New-Object IO.StreamWriter($process.StandardInput.BaseStream, $script:Utf8)
        if ($InputFile) {
            $reader = New-Object IO.StreamReader($InputFile, $script:Utf8)
            try {
                $buffer = New-Object char[] 65536
                while (($count = $reader.Read($buffer, 0, $buffer.Length)) -gt 0) { $writer.Write($buffer, 0, $count) }
            } finally { $reader.Dispose() }
        } elseif ($InputText) { $writer.Write($InputText) }
        $writer.Dispose()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) { $process.Kill(); throw "Timed out: $([IO.Path]::GetFileName($Exe))" }
        $errorText = $stderr.GetAwaiter().GetResult()
        $outputText = $stdout.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "$([IO.Path]::GetFileName($Exe)) failed ($($process.ExitCode)): $errorText" }
        return $outputText.Trim()
    } finally { $process.Dispose() }
}
function New-EmsSecret { return ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')) }
function Get-ProcessIdentity($Process, [string]$Exe) {
    return [pscustomobject]@{ id = $Process.Id; ticks = $Process.StartTime.ToUniversalTime().Ticks.ToString(); exe = [IO.Path]::GetFullPath($Exe) }
}
function Get-OwnedProcess($Identity) {
    if (-not $Identity) { return $null }
    $process = Get-Process -Id ([int]$Identity.id) -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    if ($process.StartTime.ToUniversalTime().Ticks.ToString() -ne $Identity.ticks) { throw 'Recorded PID has been reused; ownership is unknown. No process will be stopped.' }
    $allowed = @($script:JavaExe, $script:MysqldExe)
    if ($allowed -notcontains $Identity.exe) { throw 'Process record belongs to a different folder. Stop it in its original location before moving this directory.' }
    if (-not [string]::Equals($process.Path, $Identity.exe, [StringComparison]::OrdinalIgnoreCase) -and -not [string]::Equals($process.Path, $Identity.nativeExe, [StringComparison]::OrdinalIgnoreCase)) { throw 'Cannot verify the executable of a recorded process.' }
    return $process
}
function Find-FreePort([int]$First, [int]$Last, [int]$Preferred) {
    $ports = @($Preferred) + @($First..$Last | Where-Object { $_ -ne $Preferred })
    foreach ($port in $ports) {
        if ($port -lt $First -or $port -gt $Last) { continue }
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $port)
        try { $listener.Start(); return $port } catch { } finally { $listener.Stop() }
    }
    throw "No free local port in range $First-$Last. Close another EMS instance and retry."
}
function Start-EmsDatabase($State) {
    $config = "$script:DataDir\mysql-upgrade.ini"
    $base = (Get-NativePath $script:MysqlHome).Replace('\', '/')
    $db = (Get-NativePath "$script:DataDir\mysql-v3").Replace('\', '/')
    $log = (Get-NativePath "$script:LogDir\mysql-error.log").Replace('\', '/')
    Write-Utf8 $config "[mysqld]`r`nbasedir=`"$base`"`r`ndatadir=`"$db`"`r`nport=$($State.dbPort)`r`nbind-address=127.0.0.1`r`nmysqlx=OFF`r`nlocal-infile=OFF`r`nskip-log-bin`r`ncharacter-set-server=utf8mb4`r`ncollation-server=utf8mb4_0900_ai_ci`r`ndefault-time-zone=+08:00`r`ninnodb-buffer-pool-size=64M`r`nlog-error=`"$log`"`r`n"
    $nativeExe = Get-NativePath $script:MysqldExe
    $process = Start-Process -FilePath $nativeExe -ArgumentList ((Quote-EmsArgument ("--defaults-file=" + (Get-NativePath $config))) + " --no-monitor") -WorkingDirectory $script:NativeRoot -WindowStyle Hidden -PassThru
    $State.database = Get-ProcessIdentity $process $script:MysqldExe
    $State.database | Add-Member -NotePropertyName nativeExe -NotePropertyValue $nativeExe
    Save-EmsState $State
    Write-EmsLog "Bundled MySQL started on local port $($State.dbPort)."
}

function Assert-Package {
 $manifest=Get-Content -LiteralPath "$script:EmsRoot/app/build-manifest.json" -Raw -Encoding UTF8|ConvertFrom-Json
 if($manifest.buildId -ne 'v1.2.0-rc1' -or $manifest.jar -ne 'ems-1.2.0-rc1.jar' -or (Get-FileHash -LiteralPath $script:JarFile).Hash -ne $manifest.sha256){throw 'Program files are incomplete or from a different build; data retained'}

 foreach($p in @($script:JavaExe,$script:MysqlExe,$script:MysqldExe,$script:JarFile,"$script:EmsRoot\bootstrap\schema.sql","$script:EmsRoot\app\migrations\profile-v1.1.1.sql")){
  if(-not(Test-Path -LiteralPath $p -PathType Leaf)){throw "Missing runtime file: $p"}
 }
 $inventory=Get-Content -LiteralPath "$script:EmsRoot\bootstrap\runtime-sha256.json" -Raw -Encoding UTF8|ConvertFrom-Json
 
 Assert-ArtifactPath $script:EmsRoot "$script:EmsRoot\runtime"|Out-Null
 $null=@(Get-ArtifactFiles "$script:EmsRoot\runtime" -Strict)
 foreach($entry in $inventory){
  $file=[IO.Path]::GetFullPath((Join-Path $script:EmsRoot $entry.path))
  if(-not $file.StartsWith($script:EmsRoot+'\runtime\',[StringComparison]::OrdinalIgnoreCase)){throw 'Runtime inventory path escaped runtime tree'}
  if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.sha256){throw "Runtime hash mismatch: $($entry.path)"}
 }

}
function New-EmsState {return [pscustomobject]@{version='1.2.0';mode=$script:Mode;dbPort=16506;httpPort=8283;address='127.0.0.1';nativeDrive='';app=$null;database=$null}}
function Read-EmsState {
 $file="$script:DataDir\instance-upgrade.json"
 if(Test-Path -LiteralPath $file){
  $s=Get-Content -LiteralPath $file -Raw -Encoding UTF8|ConvertFrom-Json
  if($s.mode -ne $script:Mode -or $s.version -ne '1.2.0' -or $s.dbPort -lt 16506 -or $s.dbPort -gt 16530 -or $s.httpPort -in @(8083,8183)){throw 'Invalid instance ownership'}
  return $s
 }
 return $null
}
function Save-EmsState($State) {
 Write-Utf8 "$script:DataDir\instance-upgrade.json.tmp" ($State|ConvertTo-Json -Depth 6)
 Move-Item -LiteralPath "$script:DataDir\instance-upgrade.json.tmp" -Destination "$script:DataDir\instance-upgrade.json" -Force
}
function Save-EmsSecrets($Value) {
 $encrypted=@{}
 foreach($key in @('root','app','control')){$encrypted[$key]=ConvertFrom-SecureString (ConvertTo-SecureString $Value.$key -AsPlainText -Force)}
 Write-Utf8 "$script:DataDir\credentials-upgrade.dpapi.json" ($encrypted|ConvertTo-Json)
}
function Read-EmsSecrets {
 $value=Get-Content -LiteralPath "$script:DataDir\credentials-upgrade.dpapi.json" -Raw -Encoding UTF8|ConvertFrom-Json
 $plain=@{}
 foreach($key in @('root','app','control')){
  $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR((ConvertTo-SecureString $value.$key))
  try{$plain[$key]=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
 }
 return [pscustomobject]$plain
}
function Initialize-EmsDatabase {
 Assert-ArtifactPath $script:EmsRoot "$script:DataDir\mysql-v3"|Out-Null
 $db="$script:DataDir\mysql-v3";$credentials="$script:DataDir\credentials-upgrade.dpapi.json"
 if((Test-Path -LiteralPath $db) -and -not(Test-Path -LiteralPath $credentials)){throw 'Database credentials are missing; existing data will not be replaced'}
 if(-not(Test-Path -LiteralPath $credentials)){Save-EmsSecrets ([pscustomobject]@{root=New-EmsSecret;app=New-EmsSecret;control=New-EmsSecret})}
 if(Test-Path -LiteralPath $db){
  if(-not(Test-Path -LiteralPath "$db\ems-engine.ready")){throw 'Incomplete database initialization; preserve data and inspect logs'}
  return
 }
 $staging="$script:DataDir\mysql-v3-initializing"
 if(Test-Path -LiteralPath $staging){throw 'Interrupted initialization retained; inspect before retry'}
 Invoke-EmsTool $script:MysqldExe @('--no-defaults','--no-monitor','--initialize-insecure',"--basedir=$script:MysqlHome","--datadir=$staging",'--console') -TimeoutSeconds 240|Out-Null
 Write-Utf8 "$staging\ems-engine.ready" '1.2.0'
 Assert-ArtifactPath $script:EmsRoot $staging|Out-Null;Assert-ArtifactPath $script:EmsRoot $db|Out-Null
 Move-Item -LiteralPath $staging -Destination $db
}
function Write-ClientConfig($State,[string]$User='root'){
 $secret=Read-EmsSecrets;$password=if($User -eq 'root'){$secret.root}else{$secret.app}
 $file="$script:DataDir\$User-client.cnf";$nl=[Environment]::NewLine
 Write-Utf8 $file "[client]$($nl)host=127.0.0.1$($nl)port=$($State.dbPort)$($nl)protocol=TCP$($nl)user=$User$($nl)password=$password$($nl)default-character-set=utf8mb4$($nl)"
 return $file
}
function Clear-ClientConfigs {
 foreach($name in @('root-client.cnf','ems_app-client.cnf')){Remove-Item -LiteralPath "$script:DataDir\$name" -Force -ErrorAction SilentlyContinue}
}
function Wait-EmsDatabase($State){
 $config=Write-ClientConfig $State
 for($i=0;$i -lt 100;$i++){
  if(-not(Get-OwnedProcess $State.database)){throw 'Bundled MySQL exited; inspect mysql-error.log'}
  try{Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config",'--connect-timeout=2','-N','-e','SELECT 1') -TimeoutSeconds 5|Out-Null;return}catch{}
  if(-not(Test-Path -LiteralPath "$script:DataDir\accounts-upgrade.ready")){
   $secret=Read-EmsSecrets
   try{Invoke-EmsTool $script:MysqlExe @('--no-defaults','--protocol=TCP','--host=127.0.0.1',"--port=$($State.dbPort)",'--user=root','--skip-password','--connect-timeout=2') -InputText "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secret.root)';"|Out-Null
    Write-Utf8 "$script:DataDir\accounts-upgrade.ready" '1.2.0';return
   }catch{}
  }
  Start-Sleep -Seconds 1
 }
 throw 'Database readiness timed out'
}
function Invoke-EmsMaintenance([string]$Command,$State,[string]$Job='',[switch]$WebAuthorized){
 $secret=Read-EmsSecrets;$old=@{}
 $vars=@{EMS_MAINT_URL=("jdbc:mysql://127.0.0.1:"+$State.dbPort+"/"+$script:DbName+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");EMS_MAINT_USER='root';EMS_MAINT_PASSWORD=$secret.root;EMS_MAINT_JOB=$Job;EMS_MAINT_ROOT=$script:EmsRoot}
 try{
  foreach($key in $vars.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$vars[$key],'Process')}
  $main=if($Command -eq 'migrate'){'com.example.ems.upgrade.SchemaMain'}elseif($WebAuthorized){'com.example.ems.upgrade.WebMaintenanceMain'}else{'com.example.ems.upgrade.MaintenanceMain'}
  Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8',('-Dloader.main='+$main),'-cp',$script:JarFile,'org.springframework.boot.loader.launch.PropertiesLauncher',$Command) -TimeoutSeconds 600|Write-Host
 }finally{foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}}
}
function Update-EmsSchema($State){
 $config=Write-ClientConfig $State;$secret=Read-EmsSecrets
 Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config") -InputText "CREATE DATABASE IF NOT EXISTS $script:DbName CHARACTER SET utf8mb4;CREATE USER IF NOT EXISTS 'ems_app'@'127.0.0.1' IDENTIFIED BY '$($secret.app)';GRANT SELECT,INSERT,UPDATE,DELETE ON $script:DbName.* TO 'ems_app'@'127.0.0.1';"|Out-Null
 Invoke-EmsMaintenance 'migrate' $State
 Write-Utf8 "$script:DataDir\migration.json" (@{version='1.2.0';buildId='v1.2.0-rc1';schemaVersion=2;authority='flyway_schema_history';legacyManifestNotAuthoritative=$true}|ConvertTo-Json)
}
function Start-LocalDatabase($State){
 Mount-EmsNativeRoot $State;Save-EmsState $State;Initialize-EmsDatabase
 if(-not(Get-OwnedProcess $State.database)){
  $preferred=@{server=16506;portable=16507;dev=16508}[$script:Mode]
  $State.dbPort=Find-FreePort 16506 16530 $preferred;Save-EmsState $State;Start-EmsDatabase $State
 }
 Wait-EmsDatabase $State;Update-EmsSchema $State
}
function Stop-EmsApplication($State){
 $p=Get-OwnedProcess $State.app
 if($p){
  $secret=Read-EmsSecrets;Write-Utf8 "$script:DataDir\stop.request" $secret.control
  if(-not $p.WaitForExit(30000)){throw 'Application did not stop; state and database retained, no forced termination'}
 }
 $State.app=$null;Remove-Item -LiteralPath "$script:DataDir\ready" -ErrorAction SilentlyContinue;Save-EmsState $State
}
function Stop-EmsDatabase($State){
 $p=Get-OwnedProcess $State.database
 if($p){
  $cnf=Write-ClientConfig $State
  Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'--connect-timeout=3','-e','SHUTDOWN') -TimeoutSeconds 30|Out-Null
  if(-not $p.WaitForExit(30000)){throw 'Database did not stop; state retained'}
 }
 $State.database=$null;Save-EmsState $State
}
function Test-EmsReady($State){
 $stage='identity'
 try{
  if(-not(Get-OwnedProcess $State.app)){throw 'Recorded application not found'}
  $stage='ready-file'
  if(-not(Test-Path -LiteralPath "$script:DataDir\ready")){throw 'Ready file not present'}
  $stage='ready-pid'
  if([IO.File]::ReadAllText("$script:DataDir\ready").Trim() -ne [string]$State.app.id){throw 'Ready PID mismatch'}
  $stage='health'
  $request=[Net.HttpWebRequest]::Create("http://$($State.address):$($State.httpPort)/health")
  $request.Proxy=$null;$request.Timeout=4000;$request.ReadWriteTimeout=4000
  $response=$request.GetResponse();$reader=New-Object IO.StreamReader($response.GetResponseStream())
  try{return ($reader.ReadToEnd()|ConvertFrom-Json).status -eq 'UP'}finally{$reader.Dispose();$response.Dispose()}
 }catch{if($script:LastReadyStage -ne $stage){Write-EmsLog ("Readiness pending: "+$stage+" "+$_.Exception.GetType().Name);$script:LastReadyStage=$stage};return $false}
}

function Recover-EmsPendingSwitch($State){
 $folder=Join-Path $script:DataDir 'database-jobs'
 if(-not(Test-Path -LiteralPath $folder)){return}
 foreach($file in Get-ChildItem -LiteralPath $folder -Filter 'switch-journal.json' -Recurse -File){
  Assert-ArtifactPath $folder $file.FullName|Out-Null
  $journal=Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8|ConvertFrom-Json
  if($journal.mode -ne $script:Mode -or $journal.buildId -ne 'v1.2.0-rc1'){throw 'Unknown switch journal ownership'}
  if($journal.phase -notin @('PREPARED','POINTER_SWITCHED','COMMITTED','ROLLED_BACK','ROLLED_BACK_AFTER_INTERRUPTION','ABORTED')){throw 'Unknown switch journal phase; preserve for inspection'}
  if(-not $journal.ownerId -or -not $journal.ownerTicks -or -not $journal.ownerExe){throw 'Switch owner cannot be established; manual inspection required'}
  $owner=Get-Process -Id $journal.ownerId -ErrorAction SilentlyContinue
  $live=$owner -and $owner.StartTime.ToUniversalTime().Ticks.ToString() -eq [string]$journal.ownerTicks -and [string]::Equals($owner.Path,[string]$journal.ownerExe,[StringComparison]::OrdinalIgnoreCase)
  if($live){
   if($env:EMS_SWITCH_TASK -eq $file.Directory.Name){continue}
   throw 'An owned database switch is still active; wait for its result'
  }
  $status=Join-Path $file.DirectoryName 'status.json'
  $record=$null
  if(Test-Path -LiteralPath $status){$record=Get-Content -LiteralPath $status -Raw -Encoding UTF8|ConvertFrom-Json}
  if($record -and $record.state -in @('SUCCEEDED','ROLLED_BACK')){
   $journal.phase=if($record.state -eq 'SUCCEEDED'){'COMMITTED'}else{'ROLLED_BACK'}
   Write-Utf8 ($file.FullName+'.tmp') ($journal|ConvertTo-Json -Depth 6)
   Move-Item -LiteralPath ($file.FullName+'.tmp') -Destination $file.FullName -Force
  }
  if($record -and $record.state -eq 'FAILED' -and $journal.phase -eq 'PREPARED'){
   $journal.phase='ABORTED';Write-Utf8 $file.FullName ($journal|ConvertTo-Json -Depth 6)
  }
  if($journal.phase -in @('COMMITTED','ROLLED_BACK','ROLLED_BACK_AFTER_INTERRUPTION','ABORTED')){
   if((Test-Path -LiteralPath "$script:DataDir/maintenance.flag") -and [IO.File]::ReadAllText("$script:DataDir/maintenance.flag") -eq $file.Directory.Name){Remove-Item -LiteralPath "$script:DataDir/maintenance.flag","$script:DataDir/quiesced" -ErrorAction SilentlyContinue}
   continue
  }
  if($journal.old -notmatch ('^ems_up_'+$script:Mode+'_v120(_g[a-f0-9]{12})?$') -or $journal.target -notmatch ('^ems_up_'+$script:Mode+'_v120_g[a-f0-9]{12}$')){throw 'Invalid switch journal target'}
  $pointer=Join-Path $script:DataDir 'active-schema'
  if(-not(Test-Path -LiteralPath $pointer) -or [IO.File]::ReadAllText($pointer).Trim() -notin @([string]$journal.old,[string]$journal.target)){throw 'Switch journal differs from active generation; preserve data for inspection'}
  $maintenance=Join-Path $script:DataDir 'maintenance.flag'
  if((Test-Path -LiteralPath $maintenance) -and [IO.File]::ReadAllText($maintenance).Trim() -ne $file.Directory.Name){throw 'Maintenance marker belongs to another task; preserve for inspection'}
  if($State -and (Get-OwnedProcess $State.app)){Stop-EmsApplication $State;Save-EmsState $State}
  Write-Utf8 "$script:DataDir/active-schema.tmp" $journal.old
  Move-Item -LiteralPath "$script:DataDir/active-schema.tmp" -Destination "$script:DataDir/active-schema" -Force
  $script:DbName=[string]$journal.old
  $journal.phase='ROLLED_BACK_AFTER_INTERRUPTION';Write-Utf8 $file.FullName ($journal|ConvertTo-Json -Depth 6)
  $status=Join-Path $file.DirectoryName 'status.json'
  if(Test-Path -LiteralPath $status){$record=Get-Content -LiteralPath $status -Raw -Encoding UTF8|ConvertFrom-Json;$record.state='ROLLED_BACK';$record.message='中断的切换已恢复原库，请重新登录';Write-Utf8 $status ($record|ConvertTo-Json -Depth 8)}
  Remove-Item -LiteralPath "$script:DataDir/maintenance.flag","$script:DataDir/quiesced" -ErrorAction SilentlyContinue
 }
}

# Caller holds the mode lock. No remote initialization route or default credentials.
function Invoke-EmsFirstSetup($State){
 $setupPrevious=@{};$values=@{}
 try{
  Write-Host '首次使用：请设置高层账号。所有资料仅保存在本机数据库；输入 取消 可退出，下次启动继续。'
  $fields=[ordered]@{EMS_SETUP_NO='员工工号（6位数字）';EMS_SETUP_NAME='姓名';EMS_SETUP_ID='身份证号（18位）';EMS_SETUP_PHONE='手机号（11位）';EMS_SETUP_BIRTH='出生日期（yyyy-MM-dd）';EMS_SETUP_HIRE='入职日期（yyyy-MM-dd）'}
  foreach($key in $fields.Keys){$value=Read-Host $fields[$key];if($null -eq $value -or $value.Trim() -eq '取消'){throw (New-Object OperationCanceledException '已取消首次设置，现有数据保留，下次启动可继续')};$values[$key]=$value}
  $password=Read-Host '设置密码（8位以上，最多72字节，含字母和数字）' -AsSecureString
  $confirm=Read-Host '再次输入密码' -AsSecureString
  $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($password);$ptr2=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirm)
  try{$values.EMS_SETUP_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr);if($values.EMS_SETUP_PASSWORD -cne [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr2)){throw '两次输入的密码不一致；账号未创建，请重新启动'}}
  finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr);[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr2)}
  if([Text.Encoding]::UTF8.GetByteCount($values.EMS_SETUP_PASSWORD) -gt 72){throw '密码不能超过72个UTF-8字节；账号未创建'}
  $values.EMS_SETUP_URL="jdbc:mysql://127.0.0.1:$($State.dbPort)/$script:DbName"+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
  $values.EMS_DB_PASSWORD=(Read-EmsSecrets).app
  foreach($key in $values.Keys){$setupPrevious[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
  Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-Dloader.main=com.example.ems.tools.LocalSetup','-cp',$script:JarFile,'org.springframework.boot.loader.launch.PropertiesLauncher')|Write-Host
 }finally{foreach($key in $setupPrevious.Keys){[Environment]::SetEnvironmentVariable($key,$setupPrevious[$key],'Process')};$values.Clear();$password=$null;$confirm=$null}
}
