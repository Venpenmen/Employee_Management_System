$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$file=Join-Path $root 'app/ems-1.2.0-rc1.jar'
Copy-Item -LiteralPath "$root/backend/target/employee-management-system-1.2.0.jar" -Destination $file -Force
Write-ArtifactJson "$root/app/build-manifest.json" @{programVersion='1.2.0';buildId='v1.2.0-rc1';schemaVersion=2;jar='ems-1.2.0-rc1.jar';sha256=(Get-FileHash -LiteralPath $file).Hash}
Write-Host 'Application and matching build manifest staged'
