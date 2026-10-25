$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $root '.git'))) { exit 0 }
$jar = Join-Path $root 'app\ems-1.1.3.jar'
$stateFile = Join-Path $root 'data\instance.json'
if (Test-Path -LiteralPath $stateFile) {
    $state = Get-Content -LiteralPath $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($state.app -and (Get-Process -Id ([int]$state.app.id) -ErrorAction SilentlyContinue)) {
        Write-Host 'EMS is already running; skipping rebuild. Stop EMS before applying newer source changes.'
        exit 0
    }
}
$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) { throw 'Source checkout requires Maven on PATH. Install Maven, or use a complete portable release ZIP.' }
Write-Host 'Source checkout detected: rebuilding EMS v1.1.3 from current frontend/backend...'
& $maven.Source -B -f (Join-Path $root 'backend\pom.xml') -Pportable-release -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw 'Maven package failed; existing JAR was not replaced.' }
$built = Join-Path $root 'backend\target\employee-management-system-1.1.3.jar'
if (-not (Test-Path -LiteralPath $built)) { throw 'Maven output JAR not found.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($built)
try {
    $entry = $zip.GetEntry('BOOT-INF/classes/templates/login.html')
    if (-not $entry) { throw 'Built JAR has no login template.' }
    $reader = New-Object IO.StreamReader($entry.Open())
    try { $html = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($html.Contains('class="demo"')) { throw 'Built login template still contains demo account hints.' }
    if ($zip.GetEntry('BOOT-INF/classes/application.yml')) { throw 'Portable JAR unexpectedly contains development application.yml.' }
} finally { $zip.Dispose() }
Copy-Item -LiteralPath $built -Destination ($jar + '.new') -Force
Move-Item -LiteralPath ($jar + '.new') -Destination $jar -Force
Write-Host 'Current source successfully packaged into app/ems-1.1.3.jar.'
