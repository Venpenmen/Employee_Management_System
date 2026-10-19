param([switch]$Apply)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\artifact-common.ps1"
. "$PSScriptRoot\portable-common.ps1"
$baseline=Get-Content -LiteralPath (Join-Path $root '.release-evidence/v1.1.3/baseline/baseline.json') -Raw -Encoding UTF8|ConvertFrom-Json
$work=Join-Path $root '.release-work'
$dest=Join-Path $root '.release-evidence/legacy-v1.1.2'
[IO.Directory]::CreateDirectory($dest)|Out-Null
$priorFile=Join-Path $dest 'historical-cleanup.json'
if(Test-Path -LiteralPath $priorFile){
    $prior=Get-Content -LiteralPath $priorFile -Raw -Encoding UTF8|ConvertFrom-Json
    $entries=@($prior.inventory)
    if($prior.mode -eq 'apply' -and $entries.Count -eq 12 -and -not @($entries|Where-Object status -ne 'removed').Count){
        foreach($entry in $entries){if(Test-Path -LiteralPath (Join-Path $root $entry.relative)){throw 'A removed historical name has been reused; it will not be adopted for cleanup'}}
        Write-Host 'Historical cleanup already completed; evidence and inventory were left unchanged.'
        return
    }
}
$inventory=New-Object Collections.Generic.List[object]
$exported=New-Object Collections.Generic.List[object]
$remainingBudget=10MB
function Export-LegacyFile($File,[string]$Label){
    if($remainingBudget -lt 4096){return}
    if($File.Extension -notin @('.log','.txt','.png','.json')){return}
    if($File.Name -match 'credential|instance|browser-info|idea-process|source-client|package-lock'){return}
    if($File.Name -notmatch 'results|acceptance|browser|application|launcher|mysql-error|maven|build|compatibility|numeric|profile|evidence|surefire|Test'){return}
    $output=Join-Path $dest ($Label+'-'+$File.Name)
    if($File.Extension -eq '.png'){
        if($File.Length -gt 1MB -or $File.Length -gt $remainingBudget){return}
        Copy-Item -LiteralPath $File.FullName -Destination $output -Force
    }else{
        $stream=[IO.File]::OpenRead($File.FullName)
        try{$length=[int][Math]::Min(128KB,$stream.Length);$null=$stream.Seek(-$length,[IO.SeekOrigin]::End);$buffer=New-Object byte[] $length;$null=$stream.Read($buffer,0,$length)}finally{$stream.Dispose()}
        $text=Read-ArtifactTail $File.FullName 131072
        if($text.Contains([char]0)){
            $exported.Add(@{source=$File.FullName.Substring($root.Length+1);status='unreadable-null-content';bytes=$File.Length});return
        }
        $text=Protect-ArtifactText $text
        if($File.Length -gt 128KB){$text="[Earlier bytes truncated]"+[Environment]::NewLine+$text}
        [IO.File]::WriteAllText($output,$text,(New-Object Text.UTF8Encoding($false)))
    }
    $exported.Add(@{source=$File.FullName.Substring($root.Length+1);output=[IO.Path]::GetFileName($output);sha256=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash})
    $script:remainingBudget-=(Get-Item -LiteralPath $output).Length
}
# Snapshot process metadata only. Never persist command lines or secrets.
$processes=@(Get-CimInstance Win32_Process -ErrorAction Stop|Where-Object{$_.Name -match '^(java|mysqld|chrome|msedge|node)\.exe$'})
$aliases=New-Object Collections.Generic.List[string]
$aliases.Add($root+'\')
foreach($letter in [char[]]([char]'G'..[char]'Z')){
    $drive=[string]$letter+':';$target=Get-EmsDriveTarget $drive
    if($target -and [string]::Equals($target.TrimEnd('\'),('\??\'+$root).TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)){$aliases.Add($drive+'\')}
}
foreach($item in $baseline.historical){
    $path=Assert-ArtifactPath $work (Join-Path $root $item.relative)
    if(-not(Test-Path -LiteralPath $path)){continue}
    $files=@(Get-ArtifactFiles $path -Strict)
    $bytes=[long](($files|Measure-Object Length -Sum).Sum)
    if($bytes -ne [long]$item.bytes){throw "Historical directory changed after baseline: $($item.relative)"}
    $relative=$path.Substring($root.Length+1)
    foreach($process in $processes){
        foreach($alias in $aliases){
            if($process.CommandLine -and $process.CommandLine.IndexOf(($alias+$relative),[StringComparison]::OrdinalIgnoreCase) -ge 0){throw "Historical workspace is active: $relative"}
        }
    }
    foreach($stateFile in ($files|Where-Object{$_.Name -eq 'instance.json'})){
        $state=Get-Content -LiteralPath $stateFile.FullName -Raw -Encoding UTF8|ConvertFrom-Json
        foreach($identity in @($state.app,$state.database)){
            if($identity){
                $p=Get-Process -Id $identity.id -ErrorAction SilentlyContinue
                if($p -and $p.StartTime.ToUniversalTime().Ticks.ToString() -eq [string]$identity.ticks){throw "Recorded historical process is still alive: $relative"}
            }
        }
    }
    $label=[IO.Path]::GetFileName($path)
    if($item.purpose -eq 'build'){
        if(-not(Test-Path -LiteralPath (Join-Path $path 'java-unpacked'))){throw 'Unexpected build directory shape'}
    }elseif($item.purpose -eq 'acceptance'){
        if(-not @($files|Where-Object{$_.Name -eq 'ems-1.1.2.jar'}).Count){throw 'Unexpected acceptance directory shape'}
    }elseif(-not(Test-Path -LiteralPath (Join-Path $path 'Default'))){throw 'Unexpected browser profile shape'}
    if($item.purpose -ne 'browser'){
        foreach($file in ($files|Where-Object{$_.DirectoryName -match '\\logs$' -or $_.Name -eq 'acceptance-results.txt'})){Export-LegacyFile $file $label}
    }
    $inventory.Add([pscustomobject]@{relative=$relative;purpose=$item.purpose;bytes=$bytes;status='verified-stopped';exportedEvidence=$true})
}
# Preserve explicit legacy QA files, not arbitrary files from the workspace.
$legacyEvidence=Join-Path $work 'evidence-v1.1.2'
if(Test-Path -LiteralPath $legacyEvidence){
    foreach($file in (Get-ArtifactFiles $legacyEvidence -Strict)){Export-LegacyFile $file 'prior-evidence'}
}
$topNames=@('acceptance-final-v112.log','acceptance-v112.log','browser-failure.png','browser-info.json','browser-results.txt','browser-run.log','build-final-v112.log','build-v112.log','compatibility-results.txt','compatibility-run.log','idea-classpath.txt','idea-main-error.log','idea-main.log','idea-process.json','maven-v112.log','numeric-db.tsv','numeric-results.txt','v112-profile-browser.png','verify-package-v112.py','edit-compat.py','edit-docs.py','edit-v113.py','edit-io.py','edit-retention.py','edit-interrupt.py')
foreach($name in $topNames){
    $file=Join-Path $work $name
    if(Test-Path -LiteralPath $file){Export-LegacyFile (Get-Item -LiteralPath $file) 'top'}
}
Write-ArtifactJson (Join-Path $dest 'exported-evidence.json') $exported.ToArray()
Write-ArtifactJson (Join-Path $dest 'historical-cleanup.json') @{mode=$(if($Apply){'apply'}else{'report'});inventory=$inventory.ToArray();reclaimedBytes=0}
if(-not $Apply){$inventory|Format-Table relative,bytes,status;return}
$reclaimed=0L
foreach($entry in $inventory){
    $path=Assert-ArtifactPath $work (Join-Path $root $entry.relative)
    $null=@(Get-ArtifactFiles $path -Strict)
    # Recheck live processes immediately before deletion; stale report data cannot authorize removal.
    $live=@(Get-CimInstance Win32_Process -ErrorAction Stop|Where-Object{$_.Name -match '^(java|mysqld|chrome|msedge|node)\.exe$'})
    foreach($p in $live){foreach($alias in $aliases){if($p.CommandLine -and $p.CommandLine.IndexOf(($alias+$entry.relative),[StringComparison]::OrdinalIgnoreCase) -ge 0){throw 'Historical process appeared during cleanup'}}}
    Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction Stop
    $entry.status='removed';$reclaimed+=$entry.bytes
    Write-ArtifactJson (Join-Path $dest 'historical-cleanup.json') @{mode='apply';inventory=$inventory.ToArray();reclaimedBytes=$reclaimed}
}
if(Test-Path -LiteralPath $legacyEvidence){$null=Assert-ArtifactPath $work $legacyEvidence;$reclaimed+=Get-ArtifactBytes $legacyEvidence;Remove-Item -LiteralPath $legacyEvidence -Recurse -Force}
foreach($name in $topNames){$file=Assert-ArtifactPath $work (Join-Path $work $name);if(Test-Path -LiteralPath $file){$reclaimed+=(Get-Item -LiteralPath $file).Length;Remove-Item -LiteralPath $file -Force}}
$historical=[long](($baseline.directories|Where-Object name -eq '.release-work').bytes)
Write-ArtifactJson (Join-Path $dest 'historical-cleanup.json') @{mode='apply';inventory=$inventory.ToArray();reclaimedBytes=$reclaimed;historicalReclaimedBytes=$historical;scratchReclaimedBytes=($reclaimed-$historical)}
Write-Host "Historical cleanup reclaimed $reclaimed logical bytes. Real data, old releases and backups were retained."
