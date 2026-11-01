param()
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation context required'}
$run=New-ArtifactRun $root 'fixture-ownership';$ok=$false;$failure='';$saved=@{}
foreach($key in @('EMS_NETWORK_MODE','EMS_UPGRADE_ROOT')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try{
 $fixture=Join-Path $run.path '网络例外 自有夹具';[IO.Directory]::CreateDirectory($fixture)|Out-Null
 $env:EMS_NETWORK_MODE='compatibility';$env:EMS_UPGRADE_ROOT=$fixture
 $cp="$root/backend/target/test-classes;$root/backend/target/classes;"+[IO.File]::ReadAllText("$root/backend/target/dev-classpath.txt").Trim()
 $code=Invoke-ArtifactNative "$root/runtime/java/bin/java.exe" @('-cp',$cp,'com.example.ems.config.IsolatedFixtureIT',"$($run.path)/fixture-ownership-results.json") "$($run.path)/logs/fixture-ownership.log"
 if($code -ne 0){throw 'Actual managed owner verification failed'}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{
 foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
 $code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code
}
