param([string]$OutputFile)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Hash($Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function EntryHash($Entry){
 if(-not $Entry){throw 'Required archive entry missing'}
 $s=$Entry.Open();$h=[Security.Cryptography.SHA256]::Create()
 try{[BitConverter]::ToString($h.ComputeHash($s)).Replace('-','').ToLowerInvariant()}finally{$s.Dispose();$h.Dispose()}
}
function EntryJson($Zip,$Name){$s=New-Object IO.StreamReader($Zip.GetEntry($Name).Open());try{$s.ReadToEnd()|ConvertFrom-Json}finally{$s.Dispose()}}
$baseline=Get-Content "$root/docs/BASELINE_MANIFEST.json" -Raw -Encoding UTF8|ConvertFrom-Json
$originalChanges=@(foreach($f in $baseline.files){$p=Join-Path $baseline.source $f.path;if(-not(Test-Path -LiteralPath $p) -or (Hash $p) -ne $f.sha256){$f.path}})
$point=Get-Content "$root/docs/RC2_RECOVERY_POINT.json" -Raw -Encoding UTF8|ConvertFrom-Json
& git -C $root cat-file -e '514d5a0^{tree}';if($LASTEXITCODE -ne 0){throw 'Recovery tree is unavailable'}
$receipt=Get-Content "$root/.release-evidence/rc2/build-receipt.json" -Raw -Encoding UTF8|ConvertFrom-Json
$changedInputs=@(foreach($f in $receipt.inputs){$p=Join-Path $root $f.path;if(-not(Test-Path -LiteralPath $p) -or (Hash $p) -ne $f.sha256){$f.path}})
. "$PSScriptRoot/artifact-common.ps1"
$currentInputs=@(foreach($scope in @('backend/src','frontend')){foreach($f in Get-ArtifactFiles (Join-Path $root $scope) -Strict){$f.FullName.Substring($root.Length+1).Replace('\','/')}})+'backend/pom.xml'
$inputSetDifferences=@(Compare-Object @($receipt.inputs.path) $currentInputs|ForEach-Object {@{path=$_.InputObject;direction=$_.SideIndicator}})
$jarFile=Join-Path $root 'app/ems-1.2.1-next-rc2.jar';$jarHash=Hash $jarFile
if($jarHash -ne $receipt.jarSha256.ToLowerInvariant()){throw 'JAR changed since the recorded build'}
$jar=[IO.Compression.ZipFile]::OpenRead($jarFile)
try{
 foreach($m in $point.migrations){
  if((Hash (Join-Path $root $m.path)) -ne $m.sha256 -or (EntryHash $jar.GetEntry('BOOT-INF/classes/db/upgrade/'+[IO.Path]::GetFileName($m.path))) -ne $m.sha256){throw 'Historical migration raw bytes changed'}
 }
 $classFiles=0;$classDifferences=@(foreach($f in Get-ChildItem "$root/backend/target/classes" -File -Recurse){
  $rel=$f.FullName.Substring((Join-Path $root 'backend/target/classes').Length+1).Replace('\','/')
  $name=if($rel -eq 'META-INF/spring.factories'){$rel}else{'BOOT-INF/classes/'+$rel}
  $classFiles++;if((Hash $f.FullName) -ne (EntryHash $jar.GetEntry($name))){$rel}
 })
}finally{$jar.Dispose()}
$archives=@(foreach($f in Get-ChildItem "$root/dist/rc2" -Filter '*.zip' -File){
 $zip=[IO.Compression.ZipFile]::OpenRead($f.FullName)
 try{
  $entry=$zip.GetEntry('app/ems-1.2.1-next-rc2.jar');$build=EntryJson $zip 'app/build-manifest.json'
  if($entry -and (EntryHash $entry) -ne $jarHash){throw 'Packaged runtime JAR differs'}
  & git -C $root cat-file -e ($build.sourceCommit+'^{tree}');if($LASTEXITCODE -ne 0){throw 'Package source commit unavailable'}
  $actual=Hash $f.FullName
  $sum=Get-Content "$root/dist/rc2/SHA256SUMS.txt" -Encoding UTF8|Where-Object {$_ -like ('*'+$f.Name)}
  if(-not $sum -or ($sum -split '\s+')[0].ToLowerInvariant() -ne $actual){throw 'Archive SHA256SUMS mismatch'}
  [ordered]@{file=$f.Name;bytes=$f.Length;sha256=$actual;entries=$zip.Entries.Count;sourceCommit=$build.sourceCommit;runtimeJarMatches=[bool]$entry}
 }finally{$zip.Dispose()}
})
$result=[ordered]@{checkedAt=(Get-Date).ToString('o');head=((& git -C $root rev-parse HEAD)-join '');recoveryPointReadable=$true;originalSourceFiles=$baseline.files.Count;originalChangedFiles=$originalChanges;historicalMigrationRawHashes=$true;jarSha256=$jarHash;compiledMainFiles=$classFiles;compiledMainDifferences=$classDifferences;buildReceiptTests=$receipt.tests;changedReceiptInputs=$changedInputs;archives=$archives;scope='Read-only file/hash audit; no database connection or service control. Test/source changes do not rewrite preserved packages.'}
$result.add('addedRemovedReceiptInputs',$inputSetDifferences)
if($OutputFile){[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputFile),($result|ConvertTo-Json -Depth 7),(New-Object Text.UTF8Encoding($false)))}
$result|ConvertTo-Json -Depth 7
if($originalChanges.Count -or $classDifferences.Count){throw 'Source protection or JAR consistency check failed'}
