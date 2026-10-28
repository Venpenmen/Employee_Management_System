param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$JobId)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot;. "$root/launcher/artifact-common.ps1"
if(-not(Test-Path -LiteralPath "$Fixture/test-fixture.json") -or $JobId -notmatch '^[a-f0-9-]{36}$'){throw 'Synthetic interruption context required'}
$job=Join-Path $Fixture ('data/server/database-jobs/'+$JobId);Assert-ArtifactPath $Fixture $job|Out-Null
$j=Get-Content -LiteralPath (Join-Path $job 'switch-journal.json') -Raw -Encoding UTF8|ConvertFrom-Json
if($j.phase -ne 'POINTER_SWITCHED' -or [IO.File]::ReadAllText((Join-Path $job 'broker-paused')).Trim() -ne [string]$j.ownerId){throw 'Broker has not reached the exact interruption checkpoint'}
$identity=@{id=$j.ownerId;ticks=$j.ownerTicks;exe=$j.ownerExe}
$p=Test-ArtifactProcess $identity;if(-not $p){throw 'Broker identity does not match'}
$command=(Get-CimInstance Win32_Process -Filter ("ProcessId="+$p.Id)).CommandLine
if(-not $command.Contains((Join-Path $Fixture 'server/database-job.ps1').Replace('/','\')) -or -not $command.Contains($job.Replace('/','\'))){throw 'Broker command ownership mismatch'}
Stop-Process -Id $p.Id -ErrorAction Stop
if(-not $p.WaitForExit(10000)){throw 'Owned broker termination not confirmed'}
Remove-Item -LiteralPath "$Fixture/data/server/pause-switch-test" -Force
Write-Host 'PASS: exact owned broker interrupted after verified pointer checkpoint'
