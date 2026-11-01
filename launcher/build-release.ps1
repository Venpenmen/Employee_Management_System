param([string]$MavenExe,[switch]$UseExistingBuild,[string]$DeliveryDirectory='dist/rc2')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1";. "$PSScriptRoot/package-rules.ps1"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dist=Assert-ArtifactPath (Join-Path $root 'dist') (Join-Path $root $DeliveryDirectory) -AllowRoot
$deliveryNames=@('EMS员工管理系统_v1.2.1-next-rc2_便携版_Windows_x64.zip','EMS员工管理系统_v1.2.1-next-rc2_服务器版_Windows_x64.zip','EMS员工管理系统_v1.2.1-next-rc2_源码.zip','SHA256SUMS.txt')
# Refuse before staging, so retries cannot alter an existing candidate's JAR or receipt.
foreach($name in $deliveryNames){if(Test-Path -LiteralPath (Join-Path $dist $name)){throw 'Refuse to overwrite an existing delivery; choose a fresh directory under dist'}}
if(-not $UseExistingBuild){
 $MavenExe=Resolve-ArtifactTool $MavenExe 'mvn.cmd'
 & $MavenExe -B -f "$root/backend/pom.xml" test package dependency:build-classpath '-Dmdep.outputFile=target/dev-classpath.txt'
 if($LASTEXITCODE -ne 0){throw 'Maven tests/build failed'}
 & "$PSScriptRoot/stage-app.ps1"
}else{
 $receipt=Get-Content "$root/.release-evidence/rc2/build-receipt.json" -Raw -Encoding UTF8|ConvertFrom-Json
 if($receipt.failed -ne 0 -or $receipt.skipped -ne 0 -or $receipt.jarSha256 -ne (Get-FileHash "$root/app/ems-1.2.1-next-rc2.jar").Hash){throw 'Existing build has not passed tests or JAR changed'}
 foreach($file in $receipt.inputs){if((Get-FileHash -LiteralPath (Join-Path $root $file.path)).Hash -ne $file.sha256){throw 'Backend changed after tested build'}}
 $currentInputs=@(foreach($scope in @('backend/src','frontend')){foreach($f in Get-ArtifactFiles (Join-Path $root $scope) -Strict){$f.FullName.Substring($root.Length+1).Replace('\','/')}})+'backend/pom.xml'
 if(@(Compare-Object @($receipt.inputs.path) $currentInputs).Count){throw 'Files were added or removed after the tested build'}
}
New-Item -ItemType Directory -Path $dist -Force|Out-Null
foreach($delivery in @('portable','server','source')){
 $kind=if($delivery -eq 'source'){'source'}else{'runtime'}
 $name=switch($delivery){'portable'{'EMS员工管理系统_v1.2.1-next-rc2_便携版_Windows_x64.zip'}'server'{'EMS员工管理系统_v1.2.1-next-rc2_服务器版_Windows_x64.zip'}'source'{'EMS员工管理系统_v1.2.1-next-rc2_源码.zip'}}
 $dest=Join-Path $dist $name;if(Test-Path -LiteralPath $dest){throw 'Refuse to overwrite an existing delivery ZIP; archive it explicitly first'}
 $zip=[IO.Compression.ZipFile]::Open($dest,[IO.Compression.ZipArchiveMode]::Create)
 try{foreach($f in Get-RcFiles $root $kind){$rel=$f.FullName.Substring($root.Length+1).Replace('\','/');$null=[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$f.FullName,$rel,[IO.Compression.CompressionLevel]::Optimal)}}finally{$zip.Dispose()}
 & "$PSScriptRoot/verify-package.ps1" -ZipPath $dest -Kind $kind
}
Write-Host 'Candidate archives generated; run incremental acceptance before marking release candidate ready.'
$sums=@(foreach($name in $deliveryNames|Where-Object {$_ -like '*.zip'}){$file=Join-Path $dist $name;(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+$name})
[IO.File]::WriteAllLines((Join-Path $dist 'SHA256SUMS.txt'),$sums,(New-Object Text.UTF8Encoding($false)))
