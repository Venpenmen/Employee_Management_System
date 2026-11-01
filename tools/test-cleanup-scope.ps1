param([string]$ProjectRoot=(Split-Path $PSScriptRoot))
$ErrorActionPreference='Stop'
. "$ProjectRoot/launcher/artifact-common.ps1"
$fixture=Join-Path $ProjectRoot ('.codex-continuation/test-work/retention-'+[Guid]::NewGuid().ToString('N'))
Assert-ArtifactPath $ProjectRoot $fixture|Out-Null
$prior=$env:EMS_DEVELOPMENT_TASK_ID;$scope=[Guid]::NewGuid().ToString();$ok=$false
try{
 [IO.Directory]::CreateDirectory($fixture)|Out-Null
 foreach($i in 0..23){
  $id='task'+$i.ToString('00');$dir=Join-Path $fixture ('.release-evidence/1.2.1/'+$id);[IO.Directory]::CreateDirectory($dir)|Out-Null
  Write-ArtifactJson "$dir/evidence.json" @{schema=1;managed=$true;root=$fixture;path=(Join-Path $fixture ('.release-work/runs/'+$id));id=$id;keep=$false;status='succeeded';createdAt=('2026-01-'+($i+1).ToString('00'));ownerTaskId=$(if($i -lt 2){'protected-historical-task'}else{$scope})}
  [IO.File]::WriteAllText("$dir/sentinel.txt",'synthetic-'+$id)
 }
 $protected=Get-FileHash "$fixture/.release-evidence/1.2.1/task00/sentinel.txt"
 $pinPath="$fixture/.release-evidence/1.2.1/task02/evidence.json";$pin=Get-Content -LiteralPath $pinPath -Raw|ConvertFrom-Json;$pin|Add-Member -NotePropertyName retentionPinned -NotePropertyValue $true;Write-ArtifactJson $pinPath $pin
 $env:EMS_DEVELOPMENT_TASK_ID=$null;Limit-ArtifactEvidence $fixture
 if(@(Get-ChildItem "$fixture/.release-evidence/1.2.1" -Directory).Count -ne 24){throw 'Unscoped retention changed evidence'}
 $env:EMS_DEVELOPMENT_TASK_ID=$scope;Limit-ArtifactEvidence $fixture
 $actual=@(Get-ChildItem "$fixture/.release-evidence/1.2.1" -Directory).Count
 Write-Output "Scoped retention directories: $actual"
 if($actual -ne 23){throw "Unexpected scoped retention count: $actual; expected 23 (20 eligible owned, one pinned owned, two protected)"}
 if(-not(Test-Path "$fixture/.release-evidence/1.2.1/task02/sentinel.txt") -or (Test-Path "$fixture/.release-evidence/1.2.1/task03/sentinel.txt")){throw 'Verified evidence pin was ignored'}
 if(-not(Test-Path "$fixture/.release-evidence/1.2.1/task01/sentinel.txt") -or (Get-FileHash "$fixture/.release-evidence/1.2.1/task00/sentinel.txt").Hash -ne $protected.Hash){throw 'Protected historical evidence changed'}
 Write-ArtifactJson "$ProjectRoot/.codex-continuation/cleanup-scope-results.json" @{passed=$true;exitCode=0;checks=@('unscoped-zero-deletion','only-current-scope-eligible','historical-sentinel-hash-preserved','verified-pin-preserved-within-count-budget')}
 $ok=$true
}finally{
 $env:EMS_DEVELOPMENT_TASK_ID=$prior
 if(Test-Path -LiteralPath $fixture){Assert-ArtifactPath $ProjectRoot $fixture|Out-Null;Remove-Item -LiteralPath $fixture -Recurse -Force}
}
if(-not $ok){exit 1}
