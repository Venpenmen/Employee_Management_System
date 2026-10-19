param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\portable-common.ps1"
$lock = $null
$state = $null
$startedApp = $false
$startedDb = $false
try {
    $lock = Enter-EmsLock
    Assert-Package
    $state = Read-EmsState
    if ($state -and (Get-OwnedProcess $state.app)) {
        if (-not (Test-EmsReady $state)) { throw 'The recorded EMS instance is still starting or unhealthy. Run the stop BAT, then retry.' }
        Write-Host "EMS is already running: http://127.0.0.1:$($state.httpPort)"
        if (-not $NoBrowser) { Open-EmsBrowser $state.httpPort }
        exit 0
    }
    if (-not $state) { $state = New-EmsState }
    Mount-EmsNativeRoot $state
    Save-EmsState $state
    Initialize-EmsDatabase
    if (-not (Get-OwnedProcess $state.database)) {
        $state.dbPort = Find-FreePort 13306 13320 $state.dbPort
        Save-EmsState $state
        Start-EmsDatabase $state
        $startedDb = $true
    }
    Wait-EmsDatabase $state
    Restore-EmsSnapshot $state
    Update-EmsProfileSchema $state
    $state.version = '1.1.3'
    $state.httpPort = Find-FreePort 8080 8090 $state.httpPort
    $state.token = New-EmsSecret
    Save-EmsState $state
    Write-Utf8 "$script:DataDir\application.started" '1.1.3'
    $secrets = Read-EmsSecrets
    $env:EMS_DB_PORT = [string]$state.dbPort
    $env:EMS_HTTP_PORT = [string]$state.httpPort
    $env:EMS_DB_PASSWORD = $secrets.app
    $env:EMS_INSTANCE_TOKEN = $state.token
    try {
        $process = Start-Process -FilePath $script:JavaExe -ArgumentList ('-Dfile.encoding=UTF-8 -jar ' + (Quote-EmsArgument $script:JarFile) + ' --spring.profiles.active=portable') -WorkingDirectory $script:EmsRoot -WindowStyle Hidden -RedirectStandardOutput "$script:LogDir\application.log" -RedirectStandardError "$script:LogDir\application-error.log" -PassThru
        $state.app = Get-ProcessIdentity $process $script:JavaExe
        $startedApp = $true
        Save-EmsState $state
    } finally {
        Remove-Item Env:\EMS_DB_PASSWORD,Env:\EMS_INSTANCE_TOKEN,Env:\EMS_DB_PORT,Env:\EMS_HTTP_PORT -ErrorAction SilentlyContinue
    }
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        if (-not (Get-OwnedProcess $state.app)) { throw 'The backend exited. See logs\application-error.log and logs\application.log.' }
        if (Test-EmsReady $state) { $ready = $true; break }
        if ($i % 10 -eq 0) { Write-Host "Starting EMS, please wait... ($i seconds)" }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Backend readiness timed out after 120 seconds.' }
    Write-EmsLog "EMS v1.1.3 ready at http://127.0.0.1:$($state.httpPort)"
    if (-not $NoBrowser) { Open-EmsBrowser $state.httpPort }
} catch {
    Write-EmsLog "START FAILED: $($_.Exception.Message)"
    if ($state) {
        if ($startedApp) { Stop-EmsApplication $state }
        if ($startedDb) { Stop-EmsDatabase $state }
        Dismount-EmsNativeRoot $state
        Save-EmsState $state
    }
    Write-Host 'Startup failed. See logs\launcher.log, logs\mysql-error.log and logs\application-error.log.' -ForegroundColor Red
    exit 1
} finally { if ($lock) { $lock.Dispose() } }
