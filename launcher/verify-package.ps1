param([Parameter(Mandatory=$true)][string]$ZipPath,[ValidateSet('runtime','source')][string]$Kind='runtime',[string]$OutputFile)
$ErrorActionPreference='Stop';Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Split-Path $PSScriptRoot;$zip=[IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($ZipPath));$checks=New-Object Collections.Generic.List[string]
function HashEntry($Entry){$stream=$Entry.Open();$sha=[Security.Cryptography.SHA256]::Create();try{return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')}finally{$stream.Dispose();$sha.Dispose()}}
function ReadEntry($Name){$entry=$zip.GetEntry($Name);if(-not $entry){throw "Missing entry: $Name"};$reader=New-Object IO.StreamReader($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}}
try{
 $seen=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($entry in $zip.Entries){
  $name=$entry.FullName
  if($name -match '(^/|\\|:|(^|/)\.\.(/|$))' -or -not $seen.Add($name)){throw "Unsafe or duplicate ZIP entry: $name"}
  if($name -match '(^|/)(data|logs|target|dist|\.release-work|\.release-cache|\.idea)(/|$)' -or $name -match 'credentials|\.env$|server/server.json|snapshot.sql|\.xlsx$|\.partial$|\.emsbk$|\.cnf$|recovery\.key'){throw "Private artifact in package: $name"}
  . "$PSScriptRoot/artifact-common.ps1"
  . "$PSScriptRoot/package-rules.ps1"
  if(-not $allowedNames){$allowedNames=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase);foreach($f in Get-RcFiles $root $Kind){$null=$allowedNames.Add($f.FullName.Substring($root.Length+1).Replace('\','/'))}}
  $allowed=$allowedNames.Contains($name)
  if(-not $allowed){throw "Outside package whitelist: $name"}
 }
 $checks.Add('ZIP whitelist, unique paths, private-data exclusions passed')
 if($Kind -eq 'runtime'){
  foreach($entry in ((ReadEntry 'bootstrap/runtime-sha256.json')|ConvertFrom-Json)){
   $name=$entry.path.Replace('\','/');$file=$zip.GetEntry($name)
   if(-not $file -or (HashEntry $file) -ne $entry.sha256){throw "Runtime hash mismatch: $name"}
  }
  foreach($required in @('runtime/java/bin/java.exe','runtime/mysql/bin/mysqld.exe','runtime/mysql/bin/mysqldump.exe','server/start-server.ps1','server/initialize-server.ps1','app/migrations/profile-v1.1.1.sql')){if(-not $zip.GetEntry($required)){throw "Missing $required"}}
  if((HashEntry $zip.GetEntry('app/ems-1.2.1-next-rc2.jar')) -ne (Get-FileHash -LiteralPath "$root\app\ems-1.2.1-next-rc2.jar").Hash){throw 'Packaged JAR differs from tested build'}
  $build=(ReadEntry 'app/build-manifest.json')|ConvertFrom-Json
  if($build.buildId -ne 'v1.2.1-next-rc2' -or $build.sha256 -ne (HashEntry $zip.GetEntry('app/ems-1.2.1-next-rc2.jar'))){throw 'Build manifest does not match packaged JAR'}
  # Inspect the packaged JAR entirely in memory; no persistent extraction or private config.
  $memory=New-Object IO.MemoryStream;$stream=$zip.GetEntry('app/ems-1.2.1-next-rc2.jar').Open();try{$stream.CopyTo($memory)}finally{$stream.Dispose()};$memory.Position=0
  $jar=New-Object IO.Compression.ZipArchive($memory,[IO.Compression.ZipArchiveMode]::Read)
  try{$reader=New-Object IO.StreamReader($jar.GetEntry('BOOT-INF/classes/application.yml').Open());try{$cfg=$reader.ReadToEnd()}finally{$reader.Dispose()};if($cfg -match 'localhost:3306|username:\s*root|ems_db\?'){throw 'Legacy database configuration in release JAR'}}finally{$jar.Dispose();$memory.Dispose()}
  $point=(ReadEntry 'docs/RC2_RECOVERY_POINT.json')|ConvertFrom-Json
  $rawMemory=New-Object IO.MemoryStream;$rawStream=$zip.GetEntry('app/ems-1.2.1-next-rc2.jar').Open();try{$rawStream.CopyTo($rawMemory)}finally{$rawStream.Dispose()};$rawMemory.Position=0;$rawJar=New-Object IO.Compression.ZipArchive($rawMemory,[IO.Compression.ZipArchiveMode]::Read)
  try{foreach($migration in $point.migrations){$name=[IO.Path]::GetFileName($migration.path);if((HashEntry ($rawJar.GetEntry('BOOT-INF/classes/db/upgrade/'+$name))) -ne $migration.sha256){throw 'Preserved migration raw checksum differs in JAR'}}}finally{$rawJar.Dispose();$rawMemory.Dispose()}
  $checks.Add('All runtime hashes, required executables, JAR and isolated default configuration passed')
 }
 $sql=ReadEntry 'bootstrap/schema.sql'
 if($sql -match '(?is)INSERT\s+(IGNORE\s+)?INTO\s+(employee|user_account|salary_record|attendance_record|leave_request)\b'){throw 'Business rows embedded in clean schema'}
 if($Kind -eq 'source'){
  foreach($required in @('backend/pom.xml','backend/src/main/resources/db/upgrade/V3__account_security.sql','frontend/templates/security.html','frontend/static/js/security-events.js','docs/UPGRADE_TO_v1.2.1.md')){if(-not $zip.GetEntry($required)){throw "Missing complete-source artifact: $required"}}
  foreach($migration in ((ReadEntry 'docs/RC2_RECOVERY_POINT.json')|ConvertFrom-Json).migrations){if((HashEntry ($zip.GetEntry($migration.path))) -ne $migration.sha256){throw 'Preserved migration checksum differs in source ZIP'}}
  $cfg=ReadEntry 'backend/src/main/resources/application.yml'
  if($cfg -match 'username:\s*root|localhost:3306'){throw 'Original developer credentials in source package'}
 }
 if($Kind -eq 'runtime'){
  $mem=New-Object IO.MemoryStream;$stream=$zip.GetEntry('app/ems-1.2.1-next-rc2.jar').Open();try{$stream.CopyTo($mem)}finally{$stream.Dispose()};$mem.Position=0;$jar=New-Object IO.Compression.ZipArchive($mem,[IO.Compression.ZipArchiveMode]::Read)
  try{foreach($name in @('V1__canonical_business_schema.sql','V2__active_sessions.sql','V3__account_security.sql')){if(-not $jar.GetEntry('BOOT-INF/classes/db/upgrade/'+$name)){throw 'Packaged migration missing'}}}finally{$jar.Dispose();$mem.Dispose()}
  if($build.schemaVersion -ne 3 -or -not $zip.GetEntry('docs/UPGRADE_TO_v1.2.1.md')){throw 'Schema version or upgrade guide inconsistent'}
 }
 $checks.Add('Clean schema and historical profile migration preserved')
 $result=$checks -join [Environment]::NewLine
 if($OutputFile){[IO.File]::WriteAllText($OutputFile,$result,(New-Object Text.UTF8Encoding($false)))}
 Write-Host $result
}finally{$zip.Dispose()}
