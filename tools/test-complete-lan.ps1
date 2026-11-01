param([string]$NodeExe='E:/nodejs/node.exe',[ValidateRange(0,2147483647)][int]$InterfaceIndex=0)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
. "$PSScriptRoot/complete-fixture.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$addresses=@()
try{
 foreach($profile in Get-NetConnectionProfile|Where-Object NetworkCategory -eq 'Private'){
  $adapter=Get-NetAdapter -InterfaceIndex $profile.InterfaceIndex -ErrorAction Stop
  if(($adapter.HardwareInterface -or ($InterfaceIndex -gt 0 -and $profile.InterfaceIndex -eq $InterfaceIndex)) -and $adapter.Status -eq 'Up' -and ($InterfaceIndex -eq 0 -or $profile.InterfaceIndex -eq $InterfaceIndex)){$addresses+=@(Get-NetIPAddress -InterfaceIndex $profile.InterfaceIndex -AddressFamily IPv4|Where-Object {$_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*'})}
 }
}catch{$addresses=@()}
if($addresses.Count -ne 1){Write-ArtifactJson "$root/.codex-continuation/lan-environment-results.json" @{passed=$false;status='UNVERIFIED';reason='No unique existing Private physical IPv4 interface; no listener or network configuration changed';secondDeviceTested=$false};Write-Host 'LAN environment requires manual interface/device acceptance; no test was marked passed';exit 0}
$run=New-ArtifactRun $root 'complete-lan';$fixture=Join-Path $run.path '局域网 仅合成数据';$ok=$false;$failure='';$prior=$env:EMS_TEST_PASSWORD;$shell=Join-Path $PSHOME 'pwsh.exe'
try{
 Copy-CompleteFixture $run $fixture
 Write-ArtifactJson "$fixture/server-complete/network-mode.json" @{mode='lan';address=[string]$addresses[0].IPAddress}
 $env:EMS_TEST_PASSWORD='Synthetic'+[guid]::NewGuid().ToString('N')+'7'
 foreach($step in @(@('initialize',"$fixture/server-complete/initialize-server.ps1",@('-DatabaseOnly')),@('seed',"$root/launcher/seed-server-test.ps1",@('-Fixture',$fixture,'-RuntimeDirectory','server-complete')),@('start',"$fixture/server-complete/start-server.ps1",@('-NoBrowser')))){
  $code=Invoke-ArtifactNative $shell (@('-NoProfile','-File',$step[1])+$step[2]) "$($run.path)/logs/$($step[0]).log";if($code -ne 0){throw "$($step[0]) failed"}
 }
 Write-ArtifactJson "$($run.path)/interface-scope-results.json" @{existingPrivate=$true;explicitSelection=($InterfaceIndex -gt 0);physical=[bool](Get-NetAdapter -InterfaceIndex $addresses[0].InterfaceIndex).HardwareInterface;systemConfigurationChanged=$false;secondDeviceTested=$false}
 $code=Invoke-ArtifactNative $NodeExe @("$PSScriptRoot/test-complete-lan.cjs",[string]$addresses[0].IPAddress,"$($run.path)/lan-results.json") "$($run.path)/logs/http.log";if($code -ne 0){throw 'Local interface LAN checks failed'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$env:EMS_TEST_PASSWORD=$prior;$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
