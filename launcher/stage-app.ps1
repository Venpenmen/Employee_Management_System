$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$file=Join-Path $root 'app/ems-1.2.1-next-rc2.jar'
Copy-Item -LiteralPath "$root/backend/target/employee-management-system-1.2.1.jar" -Destination $file -Force
Write-ArtifactJson "$root/app/build-manifest.json" @{programVersion='1.2.1';buildId='v1.2.1-next-rc2';schemaVersion=3;sourceCommit=((& git -C $root rev-parse HEAD) -join '');jar='ems-1.2.1-next-rc2.jar';sha256=(Get-FileHash -LiteralPath $file).Hash}
Write-Host 'Application and matching build manifest staged'

$reports=@(Get-ChildItem "$root/backend/target/surefire-reports/TEST-*.xml")
$tests=0;$failed=0;$skipped=0
foreach($report in $reports){$suite=([xml][IO.File]::ReadAllText($report.FullName)).testsuite;$tests+=[int]$suite.tests;$failed+=[int]$suite.failures+[int]$suite.errors;$skipped+=[int]$suite.skipped}
if($tests -eq 0 -or $failed -ne 0 -or $skipped -ne 0){throw 'Cannot stage untested or failing build'}
$inputs=@(foreach($scope in @('backend/src','frontend')){foreach($source in Get-ArtifactFiles (Join-Path $root $scope) -Strict){@{path=$source.FullName.Substring($root.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $source.FullName).Hash}}})
$inputs+=@{path='backend/pom.xml';sha256=(Get-FileHash "$root/backend/pom.xml").Hash}
New-Item -ItemType Directory -Path "$root/.release-evidence/rc2" -Force|Out-Null
Write-ArtifactJson "$root/.release-evidence/rc2/build-receipt.json" @{tests=$tests;failed=$failed;skipped=$skipped;jarSha256=(Get-FileHash -LiteralPath $file).Hash;inputs=$inputs}
