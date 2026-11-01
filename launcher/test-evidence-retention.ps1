$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot;. "$PSScriptRoot/artifact-common.ps1"
$run=New-ArtifactRun $root 'evidence-retention';$ok=$false;$failure=''
try{
 $fake=Join-Path $run.path 'fictional-project';[IO.Directory]::CreateDirectory("$fake/.release-evidence/pinned")|Out-Null
 [IO.File]::WriteAllBytes("$fake/.release-evidence/pinned/operator-archive.bin",(New-Object byte[] 2048))
 $evidence="$fake/.release-evidence/1.2.1/fictional-task";[IO.Directory]::CreateDirectory($evidence)|Out-Null
 Write-ArtifactJson "$evidence/evidence.json" @{schema=1;managed=$true;root=$fake;id='fictional-task';path=(Join-Path $fake '.release-work/runs/fictional-task');keep=$false;status='succeeded';createdAt='2026-01-01'}
 [IO.File]::WriteAllText("$evidence/results.json",'{"passed":true}')
 $before=(Get-FileHash "$evidence/results.json").Hash;$refused=$false
 try{Limit-ArtifactEvidence $fake -MaximumBytes 1024}catch{if($_.Exception.Message -notlike '*retained all managed*'){throw};$refused=$true}
 if(-not $refused -or -not(Test-Path "$evidence/results.json") -or (Get-FileHash "$evidence/results.json").Hash -ne $before){throw 'Oversized protected archive caused evidence loss'}
 # Separate feasible-budget fixture: retain newest, remove the oldest safely.
 [IO.File]::WriteAllBytes("$fake/.release-evidence/pinned/operator-archive.bin",(New-Object byte[] 1))
 [IO.File]::WriteAllBytes("$evidence/old.bin",(New-Object byte[] 2048))
 $new="$fake/.release-evidence/1.2.1/fictional-new";[IO.Directory]::CreateDirectory($new)|Out-Null
 Write-ArtifactJson "$new/evidence.json" @{schema=1;managed=$true;root=$fake;id='fictional-new';path=(Join-Path $fake '.release-work/runs/fictional-new');keep=$false;status='succeeded';createdAt='2026-02-01'}
 Limit-ArtifactEvidence $fake -MaximumBytes 1024
 if((Test-Path $evidence) -or -not(Test-Path "$new/evidence.json")){throw 'Feasible retention did not preserve the newest evidence'}
 Write-ArtifactJson "$($run.path)/retention-results.json" @{passed=$true;checks=@('unmanaged-over-budget-refuses-before-deletion','managed-evidence-unchanged','normal-budget-evicts-oldest-preserves-newest')}
 $ok=$true
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
