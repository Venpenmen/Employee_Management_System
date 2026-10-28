param([string]$MavenExe,[switch]$UseExistingBuild)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1";. "$PSScriptRoot/package-rules.ps1"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(-not $UseExistingBuild){
 $MavenExe=Resolve-ArtifactTool $MavenExe 'mvn.cmd'
 & $MavenExe -B -f "$root/backend/pom.xml" test package dependency:build-classpath '-Dmdep.outputFile=target/dev-classpath.txt'
 if($LASTEXITCODE -ne 0){throw 'Maven tests/build failed'}
 & "$PSScriptRoot/stage-app.ps1"
}else{
 $receipt=Get-Content "$root/.release-evidence/rc1/build-receipt.json" -Raw -Encoding UTF8|ConvertFrom-Json
 if($receipt.failed -ne 0 -or $receipt.skipped -ne 0 -or $receipt.jarSha256 -ne (Get-FileHash "$root/app/ems-1.2.0-rc1.jar").Hash){throw 'Existing build has not passed tests or JAR changed'}
 foreach($file in $receipt.inputs){if((Get-FileHash -LiteralPath (Join-Path $root $file.path)).Hash -ne $file.sha256){throw 'Backend changed after tested build'}}
}
$dist=Join-Path $root 'dist';New-Item -ItemType Directory -Path $dist -Force|Out-Null
foreach($kind in @('runtime','source')){
 $name=if($kind -eq 'runtime'){'EMS_v1.2.0_Windows_Portable.zip'}else{'EMS_v1.2.0_Source.zip'}
 $dest=Join-Path $dist $name;if(Test-Path -LiteralPath $dest){throw 'Refuse to overwrite an existing delivery ZIP; archive it explicitly first'}
 $zip=[IO.Compression.ZipFile]::Open($dest,[IO.Compression.ZipArchiveMode]::Create)
 try{foreach($f in Get-RcFiles $root $kind){$rel=$f.FullName.Substring($root.Length+1).Replace('\','/');$null=[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$f.FullName,$rel,[IO.Compression.CompressionLevel]::Optimal)}}finally{$zip.Dispose()}
 & "$PSScriptRoot/verify-package.ps1" -ZipPath $dest -Kind $kind
}
Write-Host 'Candidate archives generated; run incremental acceptance before marking release candidate ready.'
