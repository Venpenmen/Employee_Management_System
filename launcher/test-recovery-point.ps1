param([string]$MavenExe)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$run=New-ArtifactRun $root 'source-recovery';$ok=$false;$failure=''
Add-Type -AssemblyName System.IO.Compression.FileSystem
try{
 $MavenExe=Resolve-ArtifactTool $MavenExe 'mvn.cmd';$git=Resolve-ArtifactTool '' 'git.exe';$archive=Join-Path $run.path 'source.zip';$fixture=Join-Path $run.path 'restored-source'
 $code=Invoke-ArtifactNative $git @('-C',$root,'archive','--format=zip',('--output='+$archive),'514d5a0') (Join-Path $run.path 'logs/archive.log');if($code -ne 0){throw 'Cannot read recovery commit'}
 $zip=[IO.Compression.ZipFile]::OpenRead($archive);try{foreach($entry in $zip.Entries){if($entry.FullName -match '(^/|:|\\|(^|/)\.\.(/|$))' -or (($entry.ExternalAttributes -shr 16) -band 61440) -eq 40960){throw 'Unsafe recovery archive'}}}finally{$zip.Dispose()}
 [IO.Compression.ZipFile]::ExtractToDirectory($archive,$fixture)
 $reference=Join-Path $root 'dist/EMS员工管理系统_v1.2.1-next_源码.zip';$zip=[IO.Compression.ZipFile]::OpenRead($reference)
 try{foreach($record in (Get-Content "$root/docs/RC2_RECOVERY_POINT.json" -Raw -Encoding UTF8|ConvertFrom-Json).migrations){
  $entry=$zip.GetEntry($record.path);$buffer=New-Object IO.MemoryStream;$stream=$entry.Open();try{$stream.CopyTo($buffer)}finally{$stream.Dispose()};$bytes=$buffer.ToArray();$buffer.Dispose();$sha=[Security.Cryptography.SHA256]::Create();try{$digest=[BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-','')}finally{$sha.Dispose()};if($digest -ne $record.sha256){throw 'Original migration fingerprint differs'}
  $target=Join-Path $fixture $record.path;$restored=[IO.File]::ReadAllText($target).Replace("`r`n","`n");$original=[Text.Encoding]::UTF8.GetString($bytes).Replace("`r`n","`n");if($restored -ne $original){throw 'Migration content differs beyond recorded line endings'};[IO.File]::WriteAllBytes($target,$bytes)
 }}finally{$zip.Dispose()}
 $code=Invoke-ArtifactNative $MavenExe @('-B','-f',(Join-Path $fixture 'backend/pom.xml'),'test') (Join-Path $run.path 'logs/recovered-maven.log');if($code -ne 0){throw 'Recovered source does not pass its baseline tests'}
 $tests=0;foreach($file in Get-ChildItem "$fixture/backend/target/surefire-reports" -Filter 'TEST-*.xml'){$suite=([xml][IO.File]::ReadAllText($file.FullName)).testsuite;if([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0){throw 'Recovery test failure'};$tests+=[int]$suite.tests}
 Write-ArtifactJson (Join-Path $run.path 'source-recovery-results.json') @{passed=$true;commit='514d5a0';mavenTests=$tests;rawMigrationsVerified=$true;runtimeOrDatabaseStarted=$false;method='actual archive extraction plus original SQL byte restoration and compilation/tests'};$ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
