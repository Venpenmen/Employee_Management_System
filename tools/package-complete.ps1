param([string]$OutputDirectory)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$manifest=Get-Content -LiteralPath "$root/app/build-manifest-complete.json" -Raw -Encoding UTF8|ConvertFrom-Json
if($manifest.buildId -ne 'v1.2.1-complete-rc1' -or (Get-FileHash "$root/app/ems-1.2.1-complete.jar").Hash -ne $manifest.sha256){throw 'Complete build artifact mismatch'}
foreach($input in $manifest.sourceInputs){if((Get-FileHash -LiteralPath (Assert-ArtifactPath $root (Join-Path $root $input.path))).Hash -ne $input.sha256){throw 'Source differs from tested complete build'}}
if(-not $OutputDirectory){$OutputDirectory=Join-Path $root 'dist/EMS_v1.2.1_complete_candidate';if(Test-Path -LiteralPath $OutputDirectory){$OutputDirectory+='_'+(Get-Date -Format yyyyMMdd-HHmmss)+'_'+[guid]::NewGuid().ToString('N').Substring(0,6)}}
$out=Assert-ArtifactPath (Join-Path $root 'dist') ([IO.Path]::GetFullPath($OutputDirectory));if(Test-Path -LiteralPath $out){throw 'Refuse existing candidate output'}
$legacy=Join-Path (Split-Path $root) 'employee-management-system_v1.2.0/dist/EMS_v1.2.0_Windows_Portable.zip'
if(-not(Test-Path -LiteralPath $legacy) -or (Get-FileHash -LiteralPath $legacy).Hash -ne '1FC499790A29B836442DB0451CB74C277B45573B87F084AD7FCFC272B0047203'){throw 'Registered immutable old runtime archive missing; no candidate produced'}
if(([IO.DriveInfo]::new([IO.Path]::GetPathRoot($out))).AvailableFreeSpace -lt 3GB){throw 'At least 3 GiB free required for independent candidate packaging; no files removed'}
[IO.Directory]::CreateDirectory($out)|Out-Null
$runtime=@{}
function Add-File($Map,[string]$Relative,[string]$Source){
 $rel=$Relative.Replace('\','/');if($rel -match '(^/|:|(^|/)\.\.(/|$))' -or $Map.ContainsKey($rel)){throw 'Unsafe or duplicate package path'}
 $file=Get-Item -LiteralPath $Source;if($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Only ordinary immutable files can enter a candidate'}
 $Map[$rel]=$file.FullName
}
foreach($rel in @('app/ems-1.2.1-complete.jar','app/version-complete.json','app/build-manifest-complete.json','app/migrations/profile-v1.1.1.sql','bootstrap/schema.sql','bootstrap/runtime-sha256.json','launcher/artifact-common.ps1','tools/complete-fixture.ps1','tools/controlled-legacy-copy.ps1','README.md','CHANGELOG.md','RELEASE_NOTES_v1.2.1.md','docs/UPGRADE_TO_v1.2.1.md','docs/TEST_REPORT.md','docs/EMS_v1.2.1_COMPLETE_DELIVERY_REPORT.md','docs/IDEA_DEV_DEBUG.md','docs/COMPLETE_DATABASE_MATRIX.md','docs/COMPLETE_CANDIDATE_GUIDE.md','docs/COMPLETE_MANUAL_ACCEPTANCE.md','docs/LOCAL_SECONDARY_RECOVERY_DESIGN.md','docs/DATABASE_COMPATIBILITY_P1_20261028.md','docs/DATABASE_COMPATIBILITY_P1_RESULTS_20261028.md')){
 if(Test-Path -LiteralPath (Join-Path $root $rel)){Add-File $runtime $rel (Join-Path $root $rel)}else{throw "Required complete delivery file missing: $rel"}
}
foreach($file in Get-ArtifactFiles "$root/server-complete" -Strict){if($file.Extension -eq '.ps1'){Add-File $runtime ('server-complete/'+$file.Name) $file.FullName}}
foreach($file in Get-ChildItem -LiteralPath $root -File -Filter '*完整候选*.bat'){Add-File $runtime $file.Name $file.FullName}
foreach($entry in (Get-Content -LiteralPath "$root/bootstrap/runtime-sha256.json" -Raw -Encoding UTF8|ConvertFrom-Json)){
 if($entry.path -notmatch '^runtime[/\\]'){throw 'Invalid runtime inventory path'}
 $file=Assert-ArtifactPath $root (Join-Path $root $entry.path);if((Get-FileHash -LiteralPath $file).Hash -ne $entry.sha256){throw 'Runtime hash mismatch'}
 Add-File $runtime $entry.path $file
}
Add-File $runtime 'compatibility/EMS_v1.2.0_Windows_Portable.zip' $legacy
$source=@{};foreach($name in $runtime.Keys){$source[$name]=$runtime[$name]}
foreach($scope in @('backend/src','frontend','tools','launcher')){
 foreach($file in Get-ArtifactFiles (Join-Path $root $scope) -Strict){
  $rel=$file.FullName.Substring($root.Length+1).Replace('\','/')
  if($file.Extension -in @('.factories','.java','.xml','.yml','.yaml','.sql','.html','.css','.js','.cjs','.json','.py','.ps1','.md','.svg','.png','.ico','.woff','.woff2','.ttf') -and $rel -notmatch '(__pycache__|\.dpapi|/target/|/test-work/)' -and -not $source.ContainsKey($rel)){Add-File $source $rel $file.FullName}
 }
}
foreach($rel in @('backend/pom.xml','.gitignore','.gitattributes')){Add-File $source $rel (Join-Path $root $rel)}
foreach($scope in @('runtime','source')){
 $map=if($scope -eq 'runtime'){$runtime}else{$source}
 foreach($name in $map.Keys){if($name -match '(^|/)(data|logs|\.git|\.release-work|\.release-evidence|\.idea|dist)(/|$)|credentials|recovery\.key|\.dpapi|network-mode\.json|server\.json|test-fixture\.json|DATABASE_COMPATIBILITY_HANDOFF_20261028|^网络运行模式设置\.bat$'){throw 'Private or protected file rejected by package whitelist'}}
}
function Write-Zip([string]$Path,$Files){
 $zip=[IO.Compression.ZipFile]::Open($Path,'Create')
 try{foreach($name in ($Files.Keys|Sort-Object)){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$Files[$name],$name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}}finally{$zip.Dispose()}
 $check=[IO.Compression.ZipFile]::OpenRead($Path)
 try{
  if($check.Entries.Count -ne $Files.Count){throw 'Archive entry count mismatch'}
  foreach($entry in $check.Entries){$sha=[Security.Cryptography.SHA256]::Create();$stream=$entry.Open();try{$digest=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')}finally{$stream.Dispose();$sha.Dispose()};if($digest -ne (Get-FileHash -LiteralPath $Files[$entry.FullName]).Hash){throw 'Archive content hash mismatch'}}
 }finally{$check.Dispose()}
}
$names=@('EMS员工管理系统_v1.2.1_便携版_Windows_x64.zip','EMS员工管理系统_v1.2.1_服务器版_Windows_x64.zip','EMS员工管理系统_v1.2.1_源码.zip')
Write-Zip (Join-Path $out $names[0]) $runtime
# Both modes use the same verified runtime; launch BAT selects an isolated mode.
[IO.File]::Copy((Join-Path $out $names[0]),(Join-Path $out $names[1]),$false)
Write-Zip (Join-Path $out $names[2]) $source
$entries=@();foreach($name in $names){$f=Join-Path $out $name;$entries+=@{file=$name;bytes=(Get-Item $f).Length;sha256=(Get-FileHash $f).Hash.ToLowerInvariant()}}
[IO.File]::WriteAllLines((Join-Path $out 'SHA256SUMS.txt'),@($entries|ForEach-Object {$_.sha256+'  '+$_.file}),(New-Object Text.UTF8Encoding($false)))
Write-ArtifactJson (Join-Path $out 'BUILD_INVENTORY.json') @{buildId=$manifest.buildId;version='1.2.1';sourceCommit=$manifest.sourceCommit;jarSha256=$manifest.sha256;runtimeEntries=$runtime.Count;sourceEntries=$source.Count;files=$entries;legacyRuntimeSha256='1fc499790a29b836442db0451cb74c277b45573b87f084ad7fcfc272b0047203';status='BUILT';acceptance='See FINAL_ACCEPTANCE.json; build alone is not acceptance'}
Write-ArtifactJson "$root/.codex-continuation/complete-package-results.json" @{passed=$true;exitCode=0;output=$out;files=$entries;sourceCommit=$manifest.sourceCommit;allEntriesRehashed=$true;historicalArtifactsOverwritten=$false}
Write-Host "Independent candidate built and archive contents rehashed: $out"
