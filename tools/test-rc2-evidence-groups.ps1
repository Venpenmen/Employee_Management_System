$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed task required'}
# This parent starts with unchanged legacy metadata. Activate the approved new
# group only after every independent child-fixture assertion has passed.
$run=New-ArtifactRun $root 'evidence-group-fixture';$ok=$false;$failure=''
$fixture=Join-Path $run.path 'sentinel';$checks=New-Object Collections.Generic.List[string]
function Check($value,[string]$label){if(-not $value){throw $label};$checks.Add($label)}
try{
 [IO.Directory]::CreateDirectory("$fixture/.codex-continuation")|Out-Null
 foreach($group in @('legacy','complete-rc2')){
  $last=if($group -eq 'legacy'){19}else{23}
  foreach($i in 0..$last){
   $id=$group+$i.ToString('00');$dir=Join-Path $fixture ('.release-evidence/1.2.1/'+$id);[IO.Directory]::CreateDirectory($dir)|Out-Null
   $m=@{schema=1;managed=$true;root=$fixture;path=(Join-Path $fixture ('.release-work/runs/'+$id));id=$id;keep=$false;status='succeeded';createdAt=('2026-01-'+($i+1).ToString('00'));ownerTaskId=$env:EMS_DEVELOPMENT_TASK_ID}
   if($group -eq 'complete-rc2'){$m.evidenceGroup=$group}else{$m.retentionPinned=$true}
   Write-ArtifactJson "$dir/evidence.json" $m;[IO.File]::WriteAllText("$dir/sentinel",'synthetic-'+$id)
  }
 }
 $bound='.release-evidence/1.2.1/complete-rc200/sentinel';$hash=(Get-FileHash "$fixture/$bound").Hash
 Write-ArtifactJson "$fixture/.codex-continuation/rc2-test-results.json" @{passed=$true;inputs=@(@{path=$bound;sha256=$hash})}
 $legacy=@(Get-ChildItem "$fixture/.release-evidence/1.2.1" -Directory|Where-Object Name -like 'legacy*'|ForEach-Object {@{path=$_.FullName;sha=(Get-FileHash "$($_.FullName)/evidence.json").Hash}})
 Limit-ArtifactEvidence $fixture -EvidenceGroup complete-rc2
 Check (@(Get-ChildItem "$fixture/.release-evidence/1.2.1" -Directory|Where-Object Name -like 'complete-rc2*').Count -eq 20) 'RC2 referenced evidence counts within its twenty-run budget'
 Check ((Get-FileHash "$fixture/$bound").Hash -eq $hash -and -not(Test-Path -LiteralPath "$fixture/.release-evidence/1.2.1/complete-rc201")) 'Referenced RC2 preserved and eligible old RC2 reclaimed'
 foreach($old in $legacy){if((Get-FileHash "$($old.path)/evidence.json").Hash -ne $old.sha){throw 'Historical manifest changed'}}
 Check ($legacy.Count -eq 20) 'All twenty historical manifests retain their original bytes'
 Limit-ArtifactEvidence $fixture
 Check (@(Get-ChildItem "$fixture/.release-evidence/1.2.1" -Directory).Count -eq 40) 'Legacy and RC2 groups remain independent under unchanged task scope'
 $before=Get-ArtifactBytes "$fixture/.release-evidence";$refused=$false
 try{Limit-ArtifactEvidence $fixture -MaximumBytes 1 -EvidenceGroup complete-rc2}catch{$refused=$true}
 Check ($refused -and (Get-ArtifactBytes "$fixture/.release-evidence") -eq $before) 'Global byte cap refuses rather than removing protected other-group evidence'
 $held=[IO.File]::Open("$fixture/.release-work/evidence-maintenance.lock",'OpenOrCreate','ReadWrite','None')
 try{$refused=$false;try{Limit-ArtifactEvidence $fixture -EvidenceGroup complete-rc2}catch [IO.IOException]{$refused=$true};Check $refused 'Groups share the existing exclusive maintenance lock'}finally{$held.Dispose()}
 Write-ArtifactJson "$($run.path)/evidence-group-results.json" @{passed=$true;checks=@($checks);historicalManifests=20;rc2Capacity=20;globalLimitBytes=200MB;taskScopeUnchanged=$true}
 $ok=$true
 # Only now apply the already verified grouping to this owned live parent.
 $run.manifest|Add-Member -NotePropertyName evidenceGroup -NotePropertyValue complete-rc2
 Write-ArtifactJson (Join-Path $run.path 'run.json') $run.manifest
}catch{$failure=$_.Exception.Message;Write-Error $failure -ErrorAction Continue}
finally{$code=Complete-ArtifactRun $run -Succeeded:$ok -OriginalError $failure;exit $code}
