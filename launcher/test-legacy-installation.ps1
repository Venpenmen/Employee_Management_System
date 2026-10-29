param([string]$NodeExe,[string]$ZipPath,[switch]$KeepArtifacts)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$NodeExe=Resolve-ArtifactTool $NodeExe 'node.exe'
$run=New-ArtifactRun $root 'legacy-installations' -KeepArtifacts:$KeepArtifacts
$ok=$false;$failure='';$old=$env:EMS_TEST_PASSWORD;$results=New-Object Collections.Generic.List[object]
$shell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"
function Run([string]$File,[string[]]$Arguments,[string]$Name){
 $code=Invoke-ArtifactNative $shell (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$File)+$Arguments) (Join-Path $run.path ('logs/'+$Name+'.log'))
 if($code -ne 0){throw ($Name+' failed')}
}
function Source-Hash([string]$Path){
 $content=(@(Get-ArtifactFiles $Path -Strict)|Sort-Object FullName|ForEach-Object {$_.FullName.Substring($Path.Length)+':'+(Get-FileHash -LiteralPath $_.FullName).Hash}) -join ';'
 $sha=[Security.Cryptography.SHA256]::Create();try{return [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($content))).Replace('-','')}finally{$sha.Dispose()}
}
try{
 foreach($label in @('113','120')){
  $f=Join-Path $run.path ('f'+$label);New-Item -ItemType Directory -Path $f|Out-Null
  if($ZipPath){
   & "$root/launcher/verify-package.ps1" -ZipPath $ZipPath
   Add-Type -AssemblyName System.IO.Compression.FileSystem
   [IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath($ZipPath),$f)
  }else{
  foreach($name in @('runtime','app','bootstrap','server','launcher')){Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $f $name) -Recurse}
  }
  Write-ArtifactJson "$f/test-fixture.json" @{run=$run.id;version='1.2.0';buildId='v1.2.0-rc1'};Register-ArtifactInstance $run $f
  Run "$f/server/initialize-server.ps1" @('-DatabaseOnly') ($label+'-initialize')
  Run "$root/launcher/prepare-legacy-installation.ps1" @('-Fixture',$f,'-ProjectRoot',$root,'-Label',$label) ($label+'-prepare')
  $source=if($label -eq '113'){Join-Path $f 'data/mysql'}else{Join-Path $f 'data/server/mysql'}
  $before=Source-Hash $source
  $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);$listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop()
  Run "$f/server/start-server.ps1" @('-Address','127.0.0.1','-Port',[string]$port,'-NoBrowser') ($label+'-automatic-upgrade-start')
  $after=Source-Hash $source;if($before -ne $after){throw 'Cold source file content changed'}
  $env:EMS_TEST_PASSWORD='SyntheticPassword123'
  $code=Invoke-ArtifactNative $NodeExe @("$root/launcher/test-upgrade-login.cjs","http://127.0.0.1:$port",(Join-Path $run.path ($label+'-login-results.json'))) (Join-Path $run.path ('logs/'+$label+'-login.log'));if($code -ne 0){throw 'Restored legacy login failed'}
  Run "$f/server/stop-server.ps1" @() ($label+'-stop')
  $pointer=[IO.File]::ReadAllText("$f/data/server/active-schema").Trim()
  Run "$f/server/upgrade-installation.ps1" @('-LegacyRoot',$f) ($label+'-repeat-upgrade')
  if([IO.File]::ReadAllText("$f/data/server/active-schema").Trim() -ne $pointer){throw 'Repeat upgrade switched database again'}
  if((Source-Hash $source) -ne $before){throw 'Repeat upgrade changed old data'}
  Run "$f/server/stop-server.ps1" @() ($label+'-final-stop')
  $results.Add(@{legacy=$label;automaticUpgrade=$true;sourceUnchanged=$true;repeatIdempotent=$true;login=$true;salaryExact=$true})
 }
 Write-ArtifactJson (Join-Path $run.path 'legacy-installation-results.json') @{passed=$true;cases=$results;data='synthetic only'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$env:EMS_TEST_PASSWORD=$old;$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
