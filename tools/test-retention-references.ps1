$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed task required'}
$run=New-ArtifactRun $root 'retention-reference-test';$ok=$false;$failure=''
try{
 $fixture=Join-Path $run.path 'sentinel';[IO.Directory]::CreateDirectory("$fixture/.codex-continuation")|Out-Null
 foreach($i in 0..23){$id='task'+$i.ToString('00');$folder="$fixture/.release-evidence/1.2.1/$id";[IO.Directory]::CreateDirectory($folder)|Out-Null;Write-ArtifactJson "$folder/evidence.json" @{schema=1;managed=$true;root=$fixture;path=(Join-Path $fixture ('.release-work/runs/'+$id));id=$id;keep=$false;status='succeeded';createdAt=('2026-01-'+($i+1).ToString('00'));ownerTaskId=$env:EMS_DEVELOPMENT_TASK_ID};[IO.File]::WriteAllText("$folder/accepted-results.json",'synthetic')}
 $bound='.release-evidence/1.2.1/task00/accepted-results.json';$before=(Get-FileHash "$fixture/$bound").Hash
 Write-ArtifactJson "$fixture/.codex-continuation/s3-module-results.json" @{passed=$true;exitCode=0;inputs=@(@{path=$bound;sha256=$before})}
 Limit-ArtifactEvidence $fixture
 if(-not(Test-Path -LiteralPath "$fixture/$bound") -or (Get-FileHash "$fixture/$bound").Hash -ne $before){throw 'Referenced oldest accepted SQL evidence removed or changed'}
 if(Test-Path -LiteralPath "$fixture/.release-evidence/1.2.1/task01"){throw 'Unreferenced eligible old evidence was not reclaimed'}
 if(@(Get-ChildItem "$fixture/.release-evidence/1.2.1" -Directory).Count -ne 20){throw 'Twenty run capacity not enforced including protected reference'}
 $id='current-parent';$folder=Join-Path $fixture ('.release-evidence/1.2.1/'+$id);[IO.Directory]::CreateDirectory($folder)|Out-Null
 Write-ArtifactJson "$folder/evidence.json" @{schema=1;managed=$true;root=$fixture;path=(Join-Path $fixture ('.release-work/runs/'+$id));id=$id;keep=$false;status='succeeded';createdAt='2025-01-01';ownerTaskId=$env:EMS_DEVELOPMENT_TASK_ID}
 Limit-ArtifactEvidence $fixture -PreserveDirectory $folder
 if(-not(Test-Path -LiteralPath "$folder/evidence.json") -or @(Get-ChildItem "$fixture/.release-evidence/1.2.1" -Directory).Count -ne 20){throw 'Current parent proof lost before caller can bind acceptance'}
 $modulePath=$env:PSModulePath
 $script=Join-Path $run.path 'child-module.ps1'
 [IO.File]::WriteAllText($script,'$ErrorActionPreference="Stop"; if(-not(Get-Command Get-FileHash -ErrorAction Stop)){exit 1}; (Get-FileHash -LiteralPath $PSCommandPath).Algorithm')
 $code=Invoke-ArtifactNative "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" @('-NoProfile','-ExecutionPolicy','Bypass','-File',$script) "$($run.path)/logs/child-module.log"
 if($code -ne 0 -or [IO.File]::ReadAllText("$($run.path)/logs/child-module.log") -notmatch 'SHA256'){throw 'Windows PowerShell native security module unavailable'}
 if($env:PSModulePath -cne $modulePath){throw 'Parent module environment was changed'}
 Write-ArtifactJson "$($run.path)/retention-reference-results.json" @{passed=$true;count=6;checks=@('oldest-module-input-sha-preserved','eligible-old-run-reclaimed','including-references-twenty-run-budget','current-parent-proof-preserved-before-binding','WinPS-native-security-module','parent-module-path-preserved')}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
