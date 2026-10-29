param([Parameter(Mandatory=$true)][string]$RuntimeZip)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$PSScriptRoot/artifact-common.ps1"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($RuntimeZip))
try{
 $seen=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($e in $zip.Entries){if($e.FullName -match '(^/|\\|:|(^|/)\.\.(/|$))' -or -not $seen.Add($e.FullName)){throw 'Unsafe ZIP path'}}
 $entry=$zip.GetEntry('app/version.json');$reader=New-Object IO.StreamReader($entry.Open());try{$v=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
 if($v.version -ne '1.2.0' -or $v.buildId -ne 'v1.2.0-rc1'){throw 'Use the matching rc1 runtime package'}
 $runtimeRecords=Get-Content "$root/bootstrap/runtime-sha256.json" -Raw -Encoding UTF8|ConvertFrom-Json
 foreach($record in $runtimeRecords){
  if($record.path -notmatch '^runtime/'){throw 'Unexpected runtime manifest path'}
  $e=$zip.GetEntry($record.path);if(-not $e){throw 'Missing runtime entry'}
  $stream=$e.Open();$sha=[Security.Cryptography.SHA256]::Create();try{$hash=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')}finally{$sha.Dispose();$stream.Dispose()}
  if($hash -ne $record.sha256){throw 'Runtime checksum mismatch'}
  $dest=Assert-ArtifactPath $root (Join-Path $root $record.path);if(Test-Path -LiteralPath $dest){if((Get-FileHash -LiteralPath $dest).Hash -ne $hash){throw 'Different existing runtime file; preserve it'};continue}
  New-Item -ItemType Directory -Path (Split-Path $dest) -Force|Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($e,$dest,$false)
 }
 Write-Host 'Matching runtime files extracted and SHA256 verified'
}finally{$zip.Dispose()}
