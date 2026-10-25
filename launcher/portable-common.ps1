# Windows PowerShell 5.1 compatible; no globally installed tools are used.
$script:EmsRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$script:DataDir = Join-Path $script:EmsRoot 'data'
$script:LogDir = Join-Path $script:EmsRoot 'logs'
$script:JavaExe = Join-Path $script:EmsRoot 'runtime\java\bin\java.exe'
$script:MysqlHome = Join-Path $script:EmsRoot 'runtime\mysql'
$script:MysqlExe = Join-Path $script:MysqlHome 'bin\mysql.exe'
$script:MysqldExe = Join-Path $script:MysqlHome 'bin\mysqld.exe'
$script:JarFile = Join-Path $script:EmsRoot 'app\ems-1.1.3.jar'
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

function Write-Utf8([string]$Path, [string]$Value) { [IO.File]::WriteAllText($Path, $Value, $script:Utf8) }
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
function Assert-Package {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'EMS requires 64-bit Windows 10/11.' }
    foreach ($path in @($script:JavaExe, $script:MysqlExe, $script:MysqldExe, $script:JarFile, "$script:EmsRoot\app\migrations\profile-v1.1.1.sql", "$script:EmsRoot\bootstrap\snapshot.sql", "$script:EmsRoot\bootstrap\snapshot-counts.json", "$script:EmsRoot\bootstrap\snapshot-manifest.json")) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Package file is missing: $path. Use the complete v1.1.3 release ZIP, or run launcher\build-release.ps1 on the development machine." }
    }
    $manifest = Get-Content -LiteralPath "$script:EmsRoot\bootstrap\snapshot-manifest.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ((Get-FileHash -LiteralPath "$script:EmsRoot\bootstrap\snapshot.sql" -Algorithm SHA256).Hash -ne $manifest.sha256) { throw 'The database snapshot checksum does not match. Extract the original release ZIP again.' }
}
function New-EmsSecret { return ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')) }
function New-EmsState { return [pscustomobject]@{ version = '1.1.3'; dbPort = 13306; httpPort = 8080; nativeDrive = ''; token = ''; app = $null; database = $null } }
function Read-EmsState {
    if (Test-Path -LiteralPath "$script:DataDir\instance.json") { return Get-Content -LiteralPath "$script:DataDir\instance.json" -Raw -Encoding UTF8 | ConvertFrom-Json }
    return $null
}
function Save-EmsState($State) {
    Write-Utf8 "$script:DataDir\instance.json.tmp" ($State | ConvertTo-Json -Depth 6)
    Move-Item -LiteralPath "$script:DataDir\instance.json.tmp" -Destination "$script:DataDir\instance.json" -Force
}
function Read-EmsSecrets { return Get-Content -LiteralPath "$script:DataDir\credentials.json" -Raw -Encoding UTF8 | ConvertFrom-Json }
function Get-ProcessIdentity($Process, [string]$Exe) {
    return [pscustomobject]@{ id = $Process.Id; ticks = $Process.StartTime.ToUniversalTime().Ticks.ToString(); exe = [IO.Path]::GetFullPath($Exe) }
}
function Get-OwnedProcess($Identity) {
    if (-not $Identity) { return $null }
    $process = Get-Process -Id ([int]$Identity.id) -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    if ($process.StartTime.ToUniversalTime().Ticks.ToString() -ne $Identity.ticks) { return $null }
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
function Initialize-EmsDatabase {
    $db = "$script:DataDir\mysql"
    $credentialFile = "$script:DataDir\credentials.json"
    if ((Test-Path -LiteralPath $db) -and -not (Test-Path -LiteralPath $credentialFile)) { throw 'Existing database has no credentials file. Restore the entire data folder from backup; it will not be overwritten.' }
    if (-not (Test-Path -LiteralPath $credentialFile)) {
        Write-Utf8 $credentialFile ([pscustomobject]@{ root = New-EmsSecret; app = New-EmsSecret } | ConvertTo-Json)
    }
    if (Test-Path -LiteralPath $db) {
        if (-not (Test-Path -LiteralPath "$db\ems-engine.ready")) { throw 'Existing database initialization is incomplete. Preserve data and consult logs; automatic overwrite is disabled.' }
        return
    }
    $staging = "$script:DataDir\mysql-initializing"
    if (Test-Path -LiteralPath $staging) {
        $archive = "$script:DataDir\mysql-incomplete-$([Guid]::NewGuid().ToString('N'))"
        Move-Item -LiteralPath $staging -Destination $archive
        Write-EmsLog "Preserved interrupted initialization at $archive"
    }
    Write-EmsLog 'Initializing bundled MySQL (first start only)...'
    Invoke-EmsTool $script:MysqldExe @('--no-defaults', '--initialize-insecure', "--basedir=$($script:MysqlHome)", "--datadir=$staging", '--console') -TimeoutSeconds 240 | Out-Null
    Write-Utf8 "$staging\ems-engine.ready" '1.1.3'
    Move-Item -LiteralPath $staging -Destination $db
}
function Write-ClientConfig($State, [string]$User = 'root') {
    $secret = Read-EmsSecrets
    $password = if ($User -eq 'root') { $secret.root } else { $secret.app }
    $path = "$script:DataDir\$User-client.cnf"
    Write-Utf8 $path "[client]`r`nhost=127.0.0.1`r`nport=$($State.dbPort)`r`nprotocol=TCP`r`nuser=$User`r`npassword=$password`r`ndefault-character-set=utf8mb4`r`n"
    return $path
}
function Start-EmsDatabase($State) {
    $config = "$script:DataDir\mysql.ini"
    $base = (Get-NativePath $script:MysqlHome).Replace('\', '/')
    $db = (Get-NativePath "$script:DataDir\mysql").Replace('\', '/')
    $log = (Get-NativePath "$script:LogDir\mysql-error.log").Replace('\', '/')
    Write-Utf8 $config "[mysqld]`r`nbasedir=`"$base`"`r`ndatadir=`"$db`"`r`nport=$($State.dbPort)`r`nbind-address=127.0.0.1`r`nmysqlx=OFF`r`nlocal-infile=OFF`r`nskip-log-bin`r`ncharacter-set-server=utf8mb4`r`ncollation-server=utf8mb4_0900_ai_ci`r`ndefault-time-zone=+08:00`r`ninnodb-buffer-pool-size=64M`r`nlog-error=`"$log`"`r`n"
    $nativeExe = Get-NativePath $script:MysqldExe
    $process = Start-Process -FilePath $nativeExe -ArgumentList (Quote-EmsArgument ("--defaults-file=" + (Get-NativePath $config))) -WorkingDirectory $script:NativeRoot -WindowStyle Hidden -PassThru
    $State.database = Get-ProcessIdentity $process $script:MysqldExe
    $State.database | Add-Member -NotePropertyName nativeExe -NotePropertyValue $nativeExe
    Save-EmsState $State
    Write-EmsLog "Bundled MySQL started on local port $($State.dbPort)."
}
function Wait-EmsDatabase($State) {
    $config = Write-ClientConfig $State
    for ($i = 0; $i -lt 120; $i++) {
        if (-not (Get-OwnedProcess $State.database)) { throw 'Bundled MySQL exited. See logs\mysql-error.log.' }
        try { Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config", '--connect-timeout=2', '-N', '-e', 'SELECT 1') -TimeoutSeconds 5 | Out-Null; return } catch { }
        if (-not (Test-Path -LiteralPath "$script:DataDir\accounts.ready")) {
            try {
                $secret = Read-EmsSecrets
                $sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secret.root)'; CREATE DATABASE IF NOT EXISTS ems_db CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'ems_app'@'127.0.0.1' IDENTIFIED BY '$($secret.app)'; GRANT SELECT,INSERT,UPDATE,DELETE ON ems_db.* TO 'ems_app'@'127.0.0.1';"
                Invoke-EmsTool $script:MysqlExe @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$($State.dbPort)", '--user=root', '--skip-password', '--connect-timeout=2') -InputText $sql -TimeoutSeconds 5 | Out-Null
                Write-Utf8 "$script:DataDir\accounts.ready" '1.1.3'
                return
            } catch { }
        }
        Start-Sleep -Seconds 1
    }
    throw 'Bundled database did not become ready. See logs\mysql-error.log.'
}
function Get-SnapshotCounts($State) {
    $config = Write-ClientConfig $State
    $expected = Get-Content -LiteralPath "$script:EmsRoot\bootstrap\snapshot-counts.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($entry in $expected.PSObject.Properties) {
        if ($entry.Name -notmatch '^[a-z_]+$') { throw 'Invalid table name in snapshot manifest.' }
        $actual = Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config", '-N', '-B', 'ems_db', '-e', "SELECT COUNT(*) FROM $($entry.Name)")
        if ([long]$actual -ne [long]$entry.Value) { throw "Restored row count mismatch for $($entry.Name)." }
    }
}
function Restore-EmsSnapshot($State) {
    if (Test-Path -LiteralPath "$script:DataDir\snapshot.ready") { return }
    if (Test-Path -LiteralPath "$script:DataDir\application.started") { throw 'A previously used database is missing its snapshot marker. Restore the complete data folder from backup; automatic reimport is refused.' }
    Write-EmsLog 'Restoring the bundled business-data snapshot (first start only)...'
    $config = Write-ClientConfig $State
    $secret = Read-EmsSecrets
    Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config") -InputText "CREATE DATABASE IF NOT EXISTS ems_db CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'ems_app'@'127.0.0.1' IDENTIFIED BY '$($secret.app)'; GRANT SELECT,INSERT,UPDATE,DELETE ON ems_db.* TO 'ems_app'@'127.0.0.1';" | Out-Null
    Write-Utf8 "$script:DataDir\accounts.ready" '1.1.3'
    Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config", '--binary-mode', 'ems_db') -InputFile "$script:EmsRoot\bootstrap\snapshot.sql" -TimeoutSeconds 600 | Out-Null
    Get-SnapshotCounts $State
    Write-Utf8 "$script:DataDir\snapshot.ready" '1.1.3'
    Write-EmsLog 'Snapshot restored and all table row counts verified.'
}
function Update-EmsProfileSchema($State) {
    $migration="$script:EmsRoot\app\migrations\profile-v1.1.1.sql"
    if(-not (Test-Path -LiteralPath $migration)){throw 'Profile upgrade SQL is missing. Use the complete v1.1.3 release.'}
    Write-EmsLog 'Checking the v1.1.3 personal-profile schema (existing data is preserved)...'
    $config=Write-ClientConfig $State
    Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config", '--binary-mode', 'ems_db') -InputFile $migration | Out-Null
}
function Test-EmsReady($State) {
    try {
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:$($State.httpPort)/internal/portable/status" -Headers @{ 'X-EMS-Token' = $State.token } -TimeoutSec 2 -ErrorAction Stop
        return $response.version -eq '1.1.3' -and [int]$response.pid -eq [int]$State.app.id
    } catch { return $false }
}
function Open-EmsBrowser([int]$Port) {
    $url = "http://127.0.0.1:$Port"
    try { Start-Process $url } catch { Write-Host "Open your browser at $url" }
}
function Stop-EmsApplication($State) {
    $process = Get-OwnedProcess $State.app
    if ($process) {
        try { Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($State.httpPort)/internal/portable/stop" -Headers @{ 'X-EMS-Token' = $State.token } -TimeoutSec 3 | Out-Null } catch { }
        if (-not $process.WaitForExit(30000)) {
            $owned = Get-OwnedProcess $State.app
            if ($owned) { Write-EmsLog 'Backend did not exit gracefully; terminating the verified portable process.'; $owned.Kill(); $owned.WaitForExit(10000) | Out-Null }
        }
    }
    $State.app = $null
}
function Stop-EmsDatabase($State) {
    $process = Get-OwnedProcess $State.database
    if ($process) {
        try {
            $config = Write-ClientConfig $State
            Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config", '--connect-timeout=3', '-e', 'SHUTDOWN') -TimeoutSeconds 30 | Out-Null
        } catch { Write-EmsLog 'Database shutdown request failed; waiting for its process to exit.' }
        if (-not $process.WaitForExit(30000)) {
            $owned = Get-OwnedProcess $State.database
            if ($owned) { Write-EmsLog 'Database did not exit gracefully; terminating the verified portable process.'; $owned.Kill(); $owned.WaitForExit(10000) | Out-Null }
        }
    }
    $State.database = $null
}
