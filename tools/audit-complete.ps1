param([Parameter(Mandatory=$true)][string]$CandidateDirectory,[string]$ValidatedDirectory)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$candidate=Assert-ArtifactPath (Join-Path $root 'dist') ([IO.Path]::GetFullPath($CandidateDirectory));$inventory=Get-Content "$candidate/BUILD_INVENTORY.json" -Raw -Encoding UTF8|ConvertFrom-Json
$state=(Get-Content "$root/.codex-continuation/STATE.json" -Raw -Encoding UTF8|ConvertFrom-Json).payload
foreach($protected in $state.protected){if((Get-FileHash -LiteralPath (Assert-ArtifactPath $root (Join-Path $root $protected.path))).Hash -ne $protected.sha256){throw 'Protected file hash changed'}}
$manifest=Get-Content "$root/app/build-manifest-complete.json" -Raw -Encoding UTF8|ConvertFrom-Json
if($manifest.sourceCommit -ne $inventory.sourceCommit -or $manifest.sha256 -ne $inventory.jarSha256){throw 'Source/build/candidate identity mismatch'}
function Hash-Stream($Stream){$sha=[Security.Cryptography.SHA256]::Create();try{return [BitConverter]::ToString($sha.ComputeHash($Stream)).Replace('-','')}finally{$sha.Dispose()}}
$migrationCount=0;$entries=0;$sourceInputs=0;$validatedFiles=0
foreach($file in $inventory.files){
 $path=Assert-ArtifactPath $candidate (Join-Path $candidate $file.file)
 if((Get-FileHash -LiteralPath $path).Hash -ne $file.sha256){throw 'Final ZIP checksum mismatch'}
 $zip=[IO.Compression.ZipFile]::OpenRead($path);$validated=$null
 if($ValidatedDirectory -and $file.file -notlike '*源码.zip'){
  $validatedRoot=Assert-ArtifactPath (Join-Path $root 'dist') ([IO.Path]::GetFullPath($ValidatedDirectory))
  $validated=[IO.Compression.ZipFile]::OpenRead((Join-Path $validatedRoot $file.file))
 }
 try{
  foreach($entry in $zip.Entries){
   $entries++
   if($entry.FullName -match '(^/|:|\\|(^|/)\.\.(/|$)|(^|/)(data|logs|dist|\.git|\.idea|\.release-work|\.release-evidence)(/|$)|\.dpapi|recovery\.key|credentials-upgrade|network-mode\.json|(^|/)server\.json$|test-fixture\.json|DATABASE_COMPATIBILITY_HANDOFF_20261028|^网络运行模式设置\.bat$)'){throw 'Private/unsafe archive entry found'}
   if($validated -and $entry.FullName -notlike '*.md'){
    $old=$validated.GetEntry($entry.FullName);if(-not $old){throw 'Final runnable payload differs from extracted tested package'}
    $a=$entry.Open();$b=$old.Open();try{if((Hash-Stream $a) -ne (Hash-Stream $b)){throw 'Final executable/runtime/config/tool bytes differ from tested package'}}finally{$a.Dispose();$b.Dispose()};$validatedFiles++
   }
  }
  $jar=$zip.GetEntry('app/ems-1.2.1-complete.jar');if(-not $jar){throw 'Complete runnable JAR missing'}
  $stream=$jar.Open();try{if((Hash-Stream $stream) -ne $inventory.jarSha256){throw 'JAR differs across deliveries'}}finally{$stream.Dispose()}
  $memory=New-Object IO.MemoryStream;$stream=$jar.Open();try{$stream.CopyTo($memory)}finally{$stream.Dispose()};$memory.Position=0
  $nested=New-Object IO.Compression.ZipArchive($memory,[IO.Compression.ZipArchiveMode]::Read,$true)
  try{foreach($migration in $state.protected|Where-Object path -like 'backend/src/main/resources/db/upgrade/*'){
   $rel=$migration.path.Replace('backend/src/main/resources/','');$sql=$nested.GetEntry('BOOT-INF/classes/'+$rel);if(-not $sql){throw 'JAR historical migration absent'};$stream=$sql.Open();try{if((Hash-Stream $stream) -ne $migration.sha256){throw 'JAR migration checksum altered'}}finally{$stream.Dispose()};$migrationCount++
  }}finally{$nested.Dispose();$memory.Dispose()}
  if($file.file -like '*源码.zip'){
   foreach($input in $manifest.sourceInputs){$entry=$zip.GetEntry($input.path);if(-not $entry){throw 'Full source delivery omits tested input'};$stream=$entry.Open();try{if((Hash-Stream $stream) -ne $input.sha256){throw 'Source archive differs from built source'}}finally{$stream.Dispose()};$sourceInputs++}
  }
 }finally{if($validated){$validated.Dispose()};$zip.Dispose()}
}
if($migrationCount -ne 9 -or $sourceInputs -lt 1){throw 'Migration/source coverage incomplete'}
$active=@();foreach($dir in Get-ChildItem "$root/.release-work/runs" -Directory -ErrorAction SilentlyContinue){$record=Join-Path $dir.FullName 'run.json';if(Test-Path -LiteralPath $record){$r=Get-Content $record -Raw -Encoding UTF8|ConvertFrom-Json;if($r.ownerTaskId -eq $env:EMS_DEVELOPMENT_TASK_ID){$active+=@{id=$r.id;status=$r.status;keep=$r.keep}}}}
if($active.Count){throw 'Owned full workspaces remain; audit and resolve before completion'}
Write-ArtifactJson "$root/.codex-continuation/complete-audit-results.json" @{passed=$true;exitCode=0;protectedHashes=@($state.protected).Count;zipEntries=$entries;sourceInputs=$sourceInputs;historicalMigrationChecks=$migrationCount;allPackageJarsIdentical=$true;privatePathsRejected=$true;ownedFullWorkspaces=0;sourceCommit=$inventory.sourceCommit;jarSha256=$inventory.jarSha256;candidate=$candidate;testedRuntimeFilesMatched=$validatedFiles;projectFileBytes=(Get-ArtifactBytes $root);diskFreeBytes=([IO.DriveInfo]::new([IO.Path]::GetPathRoot($root))).AvailableFreeSpace;remoteOperations=$false;formalRelease=$false}
Write-Host 'Final candidate hashes, source inputs, original migrations, protected files and owned cleanup verified'
