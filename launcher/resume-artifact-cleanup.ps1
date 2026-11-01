param([Parameter(Mandatory=$true)][string]$ContextPath)
$ErrorActionPreference='Stop'
# Correct root is the project parent, never an arbitrary user-supplied cleanup root.
$root=Split-Path $PSScriptRoot
. "$PSScriptRoot/artifact-common.ps1"
$run=Read-ArtifactContext $root $ContextPath
$oldOwner=Get-Process -Id $run.manifest.ownerId -ErrorAction SilentlyContinue
if($oldOwner){if(-not $oldOwner.StartTime){throw 'Old owner identity unknown; retained'};if($oldOwner.StartTime.ToUniversalTime().Ticks.ToString() -eq [string]$run.manifest.ownerTicks){throw 'Original task still running; refused takeover'}}
$run.lock=[IO.File]::Open((Join-Path $root ('.release-work/runs/.locks/'+$run.id+'.lock')),'OpenOrCreate','ReadWrite','None')
try{
 $null=Assert-ArtifactPath (Join-Path $root '.release-work/runs') $run.path
 $null=@(Get-ArtifactFiles $run.path -Strict)
 $old=@{ownerId=$run.manifest.ownerId;ownerTicks=$run.manifest.ownerTicks;reason=$run.manifest.reason}
 $run.manifest|Add-Member -NotePropertyName previousOwner -NotePropertyValue $old -Force
 $run.manifest.ownerId=$PID;$run.manifest.ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString()
 Write-ArtifactJson (Join-Path $run.path 'run.json') $run.manifest
 # The standard finalizer revalidates and stops every recorded owned process; identity failures retain all artifacts.
 $code=Complete-ArtifactRun $run -Succeeded:($run.manifest.status -eq 'succeeded') -OriginalError ([string]$old.reason)
 exit $code
}finally{if($run.lock){$run.lock.Dispose()}}
