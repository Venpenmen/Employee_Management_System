param([Parameter(Mandatory=$true)][string]$MavenExe)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
if(-not(Test-Path -LiteralPath $MavenExe -PathType Leaf)){throw 'Offline Maven executable required'}
function Inputs {
 @('backend/pom.xml')+@(foreach($scope in @('backend/src','frontend','server-complete')){foreach($file in Get-ArtifactFiles (Join-Path $root $scope) -Strict){$rel=$file.FullName.Substring($root.Length+1).Replace('\','/');if($rel -notin @('server-complete/network-mode.json','server-complete/server.json')){$rel}}}) | Sort-Object | ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $root $_) -Algorithm SHA256).Hash}}
}
foreach($mode in @('dev','portable','server')){
 $state=Join-Path $root ("data/complete/$mode/instance-upgrade.json")
 if(Test-Path -LiteralPath $state){$s=Get-Content -LiteralPath $state -Raw -Encoding UTF8|ConvertFrom-Json;if($s.app -or $s.database -or $s.nativeDrive){throw 'Complete candidate has recorded resources; stop only its owned instance before staging'}}
}
$before=@(Inputs);$started=Get-Date
& $MavenExe -o -B -f "$root/backend/pom.xml" package dependency:build-classpath '-Dmdep.outputFile=target/dev-classpath.txt'
if($LASTEXITCODE -ne 0){throw 'Complete build/tests failed; staged JAR unchanged'}
$after=@(Inputs)
if(($before|ConvertTo-Json -Depth 3 -Compress) -cne ($after|ConvertTo-Json -Depth 3 -Compress)){throw 'Source changed during build; refuse mismatched candidate'}
$tests=0;$failures=0;$skipped=0
foreach($file in Get-ChildItem "$root/backend/target/surefire-reports/TEST-*.xml" -File|Where-Object LastWriteTime -ge $started){$suite=([xml][IO.File]::ReadAllText($file.FullName)).testsuite;$tests+=[int]$suite.tests;$failures+=[int]$suite.failures+[int]$suite.errors;$skipped+=[int]$suite.skipped}
if($tests -le 0 -or $failures -ne 0 -or $skipped -ne 0){throw 'Fresh complete test evidence missing or failed'}
$jar="$root/app/ems-1.2.1-complete.jar"
Copy-Item -LiteralPath "$root/backend/target/employee-management-system-1.2.1.jar" -Destination ($jar+'.partial')
Move-Item -LiteralPath ($jar+'.partial') -Destination $jar -Force
$head='unversioned-local'
if(Test-Path -LiteralPath (Join-Path $root '.git')){$head=(& git -C $root rev-parse HEAD)-join '';if($LASTEXITCODE -ne 0){throw 'Cannot identify local source commit'}}
$hash=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
Write-ArtifactJson "$root/app/version-complete.json" @{version='1.2.1';buildId='v1.2.1-complete-rc1';schemaVersion=3}
Write-ArtifactJson "$root/app/build-manifest-complete.json" @{programVersion='1.2.1';buildId='v1.2.1-complete-rc1';schemaVersion=3;sourceCommit=$head;jar='ems-1.2.1-complete.jar';sha256=$hash;sourceInputs=$after}
$folder=Join-Path $root '.codex-continuation'
Write-ArtifactJson "$folder/complete-build-results.json" @{passed=$true;exitCode=0;tests=$tests;failures=$failures;skipped=$skipped;jarSha256=$hash;sourceCommit=$head;inputs=$after}
Write-Host "Complete candidate staged separately; tests=$tests; historical RC2 artifacts unchanged"
