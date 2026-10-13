$ErrorActionPreference = "SilentlyContinue"
$projectDir = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $projectDir "logs"
$pidFile = Join-Path $logDir "ems.pid"

if (-not (Test-Path $pidFile)) {
    Write-Host "No launcher process record was found."
    exit 0
}

$savedPid = Get-Content $pidFile | Select-Object -First 1
if ($savedPid -match "^\d+$") {
    $process = Get-Process -Id ([int]$savedPid) -ErrorAction SilentlyContinue
    if ($process) {
        & taskkill.exe /PID $savedPid /T /F | Out-Null
        Write-Host "EMS has been stopped." -ForegroundColor Green
    } else {
        Write-Host "The recorded process has already exited."
    }
}
Remove-Item $pidFile -Force
