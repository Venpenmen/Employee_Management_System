param([Parameter(Mandatory=$true)][string]$PortableZip,[Parameter(Mandatory=$true)][string]$ServerZip)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$PSScriptRoot/artifact-common.ps1"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$run=New-ArtifactRun $root 'package-smoke';$ok=$false;$failure='';$oldPassword=$env:EMS_TEST_PASSWORD
$shell="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"
function Run($Script,$Arguments,$Label){$code=Invoke-ArtifactNative $shell (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$Script)+$Arguments) (Join-Path $run.path ('logs/'+$Label+'.log'));if($code -ne 0){throw "$Label failed: $code"}}
try{
 foreach($mode in @('portable','server')){
  $archive=if($mode -eq 'portable'){$PortableZip}else{$ServerZip}
  Run "$PSScriptRoot/verify-package.ps1" @('-ZipPath',$archive,'-Kind','runtime') ($mode+'-zip-verification')
  $fixture=Join-Path $run.path ($mode+' 完整解压');[IO.Directory]::CreateDirectory($fixture)|Out-Null
  [IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath($archive),$fixture)
  Write-ArtifactJson "$fixture/test-fixture.json" @{run=$run.id;version='1.2.1'};Register-ArtifactInstance $run $fixture
  Run "$PSScriptRoot/test-first-setup-cancel.ps1" @('-Fixture',$fixture,'-Mode',$mode) ($mode+'-cancel-first-setup')
  $env:EMS_TEST_PASSWORD='Qa'+[Guid]::NewGuid().ToString('N')+'7'
  Run "$fixture/server/initialize-server.ps1" @('-Mode',$mode,'-DatabaseOnly') ($mode+'-initialize')
  Run "$PSScriptRoot/seed-server-test.ps1" @('-Fixture',$fixture,'-Mode',$mode) ($mode+'-seed')
  Run "$fixture/server/start-server.ps1" @('-Mode',$mode,'-Address','127.0.0.1','-NoBrowser') ($mode+'-start')
  $port=if($mode -eq 'portable'){8381}else{8383}
  if((Invoke-RestMethod "http://127.0.0.1:$port/health").status -ne 'UP'){throw 'Extracted package health failed'}
  $page=Invoke-WebRequest "http://127.0.0.1:$port/login" -UseBasicParsing -SessionVariable webJar
  if($page.StatusCode -ne 200 -or $page.Content -notmatch '_csrf'){throw 'Extracted package frontend/CSRF unavailable'}
  $csrf=[regex]::Match($page.Content,'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
  if(-not $csrf){throw 'Login CSRF token missing'}
  $loggedIn=Invoke-WebRequest "http://127.0.0.1:$port/login" -Method Post -WebSession $webJar -Body @{employeeNo='260001';password=$env:EMS_TEST_PASSWORD;_csrf=$csrf} -UseBasicParsing
  if($loggedIn.BaseResponse.ResponseUri.AbsolutePath -ne '/dashboard'){throw 'Extracted package actual login failed'}
  Run "$fixture/server/stop-server.ps1" @('-Mode',$mode) ($mode+'-stop')
  $moved=Join-Path $run.path ($mode+' 移动后');Assert-ArtifactPath $run.path $fixture|Out-Null;Assert-ArtifactPath $run.path $moved|Out-Null
  Move-Item -LiteralPath $fixture -Destination $moved;$fixture=$moved;Register-ArtifactInstance $run $fixture
  Run "$fixture/server/start-server.ps1" @('-Mode',$mode,'-Address','127.0.0.1','-NoBrowser') ($mode+'-moved-start')
  if((Invoke-RestMethod "http://127.0.0.1:$port/health").status -ne 'UP'){throw 'Moved package failed readiness'}
  Run "$fixture/server/stop-server.ps1" @('-Mode',$mode) ($mode+'-moved-stop')

 }
 Write-ArtifactJson (Join-Path $run.path 'package-smoke-results.json') @{passed=$true;modes=@('portable','server');checks=@('zip-whitelist-hashes','Unicode-space-extraction','independent-init','repeat-setup-refused','default-port-start','health','frontend-csrf','safe-stop','moved-install-root-rebind');physicalWindowsCleanMachine='not-tested'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$env:EMS_TEST_PASSWORD=$oldPassword;$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
