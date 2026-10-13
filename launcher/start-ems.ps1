$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $projectDir "backend"
$logDir = Join-Path $projectDir "logs"
$pidFile = Join-Path $logDir "ems.pid"
$launcherLog = Join-Path $logDir "launcher.log"
$appLog = Join-Path $logDir "application.log"
$appErrorLog = Join-Path $logDir "application-error.log"
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Write-LauncherLog([string]$message) {
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $message
    $line | Tee-Object -FilePath $launcherLog -Append
}

function Test-Port([int]$port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect("127.0.0.1", $port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(500)
        if ($ok -and $client.Connected) {
            $client.EndConnect($async)
            $client.Close()
            return $true
        }
        $client.Close()
    } catch { }
    return $false
}

if (Test-Port 8080) {
    Write-LauncherLog "Port 8080 is already active. Opening the web page."
    Start-Process "http://localhost:8080"
    exit 0
}

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) { $maven = Get-Command mvn -ErrorAction SilentlyContinue }

if (-not $maven) {
    $ideaRoots = @(
        "$env:ProgramFiles\JetBrains",
        "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate",
        "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Community Edition",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps"
    ) | Where-Object { $_ -and (Test-Path $_) }

    foreach ($root in $ideaRoots) {
        $candidate = Get-ChildItem -Path $root -Filter "mvn.cmd" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "plugins[\\/]maven[\\/]lib[\\/]maven3[\\/]bin[\\/]mvn.cmd$" } |
            Select-Object -First 1
        if ($candidate) { $maven = $candidate; break }
    }
}

if (-not $maven) {
    Write-LauncherLog "Maven was not found. Install Maven or keep IntelliJ IDEA installed."
    Write-Host "Maven was not found." -ForegroundColor Red
    Write-Host "Install Maven or keep IntelliJ IDEA installed, then try again."
    exit 1
}

$java = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $java) { $java = Get-Command java -ErrorAction SilentlyContinue }
if (-not $java) {
    $javaRoots = @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\JetBrains",
        "$env:LOCALAPPDATA\Programs",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps"
    ) | Where-Object { $_ -and (Test-Path $_) }
    foreach ($root in $javaRoots) {
        $candidate = Get-ChildItem -Path $root -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "[\\/]bin[\\/]java.exe$" } |
            Select-Object -First 1
        if ($candidate) { $java = $candidate; break }
    }
}
if (-not $java) {
    Write-LauncherLog "Java was not found."
    Write-Host "Java was not found. Install JDK 17 or IntelliJ IDEA." -ForegroundColor Red
    exit 1
}

$javaPath = if ($java.Path) { $java.Path } else { $java.FullName }
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaPath)
$env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
$env:EMS_BROWSER_AUTO_OPEN = "false"

$mavenPath = if ($maven.Path) { $maven.Path } else { $maven.FullName }
Write-LauncherLog "Using Java: $javaPath"
Write-LauncherLog "Using Maven: $mavenPath"
Write-LauncherLog "Starting EMS..."
if (Test-Path $appLog) { Remove-Item $appLog -Force }
if (Test-Path $appErrorLog) { Remove-Item $appErrorLog -Force }

$process = Start-Process -FilePath $mavenPath `
    -ArgumentList @("spring-boot:run", "-DskipTests") `
    -WorkingDirectory $backendDir `
    -RedirectStandardOutput $appLog `
    -RedirectStandardError $appErrorLog `
    -WindowStyle Hidden `
    -PassThru

$process.Id | Set-Content -Path $pidFile -Encoding ASCII
Write-LauncherLog "Background process PID: $($process.Id)"

for ($i = 1; $i -le 120; $i++) {
    Start-Sleep -Seconds 1
    if ($process.HasExited) {
        Write-LauncherLog "The application exited early. Exit code: $($process.ExitCode)"
        Write-Host "Startup failed. Check logs\application-error.log." -ForegroundColor Red
        exit 1
    }
    if (Test-Port 8080) {
        Write-LauncherLog "Startup succeeded. Opening the browser."
        Start-Process "http://localhost:8080"
        exit 0
    }
    if ($i % 10 -eq 0) { Write-Host "Starting, please wait... ($i seconds)" }
}

Write-LauncherLog "Port 8080 was not available after 120 seconds."
Write-Host "Startup timed out. Check MySQL and the credentials in application.yml." -ForegroundColor Red
exit 1
