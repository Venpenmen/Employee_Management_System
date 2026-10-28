param([string]$NodeExe,[switch]$KeepArtifacts)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$NodeExe=Resolve-ArtifactTool $NodeExe 'node.exe'
$run=New-ArtifactRun $root 'database-management' -KeepArtifacts:$KeepArtifacts;$ok=$false;$failure='';$fixture=Join-Path $run.path 'f';$old=$env:EMS_TEST_PASSWORD
$shell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"
try{
 New-Item -ItemType Directory -Path $fixture|Out-Null
 foreach($name in @('runtime','app','bootstrap','server','launcher')){Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $fixture $name) -Recurse}
 Write-ArtifactJson "$fixture/test-fixture.json" @{run=$run.id;version='1.2.0';buildId='v1.2.0-rc1'}
 Register-ArtifactInstance $run $fixture
 $env:EMS_TEST_PASSWORD='Qa'+[Guid]::NewGuid().ToString('N')+'7'
 foreach($step in @(@('initialize',"$fixture/server/initialize-server.ps1",@('-DatabaseOnly')),@('seed',"$root/launcher/seed-server-test.ps1",@('-Fixture',$fixture)))){
  $code=Invoke-ArtifactNative $shell (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$step[1])+$step[2]) (Join-Path $run.path ('logs/'+$step[0]+'.log'));if($code -ne 0){throw ($step[0]+' failed')}
 }
 $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);$listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop()
 $code=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$fixture/server/start-server.ps1",'-Address','127.0.0.1','-Port',[string]$port,'-NoBrowser') (Join-Path $run.path 'logs/start.log');if($code -ne 0){throw 'Start failed'}
 $code=Invoke-ArtifactNative $NodeExe @("$root/launcher/test-database-http.cjs","http://127.0.0.1:$port",(Join-Path $run.path 'database-http-results.json'),$fixture) (Join-Path $run.path 'logs/database-http.log')
 if($code -ne 0){throw 'Database HTTP failed'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 try{
  $base=Join-Path $fixture 'data/server/database-jobs'
  if(Test-Path -LiteralPath $base){foreach($folder in Get-ChildItem -LiteralPath $base -Directory){foreach($log in Get-ChildItem -LiteralPath $folder.FullName -File -Filter '*.log'){
   $text=Protect-ArtifactText (Read-ArtifactTail $log.FullName 65536);[IO.File]::WriteAllText((Join-Path $run.path ('logs/job-'+$folder.Name+'-'+$log.Name)),$text,(New-Object Text.UTF8Encoding($false)))
  }}}
 }catch{Write-Warning 'Job diagnostic capture incomplete'}
 $env:EMS_TEST_PASSWORD=$old
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
