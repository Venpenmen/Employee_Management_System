param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) { throw 'Maven (mvn.cmd) is required for source development launch.' }
if (-not $env:EMS_DB_PASSWORD) {
    $secret = Read-Host 'Enter local MySQL development database password (not saved)' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { $env:EMS_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
try {
    Write-Host 'Starting development EMS using the same default profile as IDEA (MySQL localhost:3306/ems_db).'
    Write-Host 'Press Ctrl+C to stop. The portable distribution is not used.'
    & $maven.Source -f (Join-Path $root 'backend\pom.xml') spring-boot:run
    exit $LASTEXITCODE
} finally { Remove-Item Env:\EMS_DB_PASSWORD -ErrorAction SilentlyContinue }
