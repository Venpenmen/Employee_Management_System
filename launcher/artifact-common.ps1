# Windows PowerShell 5.1. All deletion is scoped to an explicitly owned run.
function Write-ArtifactJson($Path,$Value) {
    [IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)))
}
function Assert-ArtifactPath([string]$Root,[string]$Path,[switch]$AllowRoot) {
    $base=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    $full=[IO.Path]::GetFullPath($Path).TrimEnd('\')
    if((-not $AllowRoot -or $full -ne $base) -and -not $full.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path is outside its declared root: $full"}
    $probe=$full
    while($probe){
        if(Test-Path -LiteralPath $probe){
            if((Get-Item -LiteralPath $probe -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw "Reparse path refused: $probe"}
        }
        $parent=[IO.Path]::GetDirectoryName($probe)
        if($parent -eq $probe){break};$probe=$parent
    }
    return $full
}
function Get-ArtifactFiles([string]$Path,[switch]$Strict) {
    if(-not(Test-Path -LiteralPath $Path)){return}
    $stack=New-Object Collections.Generic.Stack[string];$stack.Push([IO.Path]::GetFullPath($Path))
    while($stack.Count){
        foreach($entry in (Get-ChildItem -LiteralPath $stack.Pop() -Force -ErrorAction Stop)){
            if($entry.Attributes -band [IO.FileAttributes]::ReparsePoint){if($Strict){throw "Linked entry refused: $($entry.FullName)"};continue}
            if($entry.PSIsContainer){$stack.Push($entry.FullName)}else{$entry}
        }
    }
}
function Get-ArtifactBytes([string]$Path) {return [long]((Get-ArtifactFiles $Path|Measure-Object Length -Sum).Sum)}
function Get-ArtifactVersion([string]$Root){
 $pom=Join-Path $Root 'backend/pom.xml'
 if(Test-Path -LiteralPath $pom){return ([xml][IO.File]::ReadAllText($pom)).project.version}
 $manifest=Join-Path $Root 'app/version.json'
 if(-not(Test-Path -LiteralPath $manifest)){throw 'Project or runtime version manifest is missing'}
 $version=(Get-Content -LiteralPath $manifest -Raw -Encoding UTF8|ConvertFrom-Json).version
 if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid runtime version manifest'}
 return $version
}
function New-ArtifactRun([string]$Root,[string]$Kind,[switch]$KeepArtifacts){
    $rootPath=Assert-ArtifactPath $Root $Root -AllowRoot
    if($Kind -notmatch '^[a-z-]+$'){throw 'Invalid task kind'}
    $runs=Join-Path $rootPath '.release-work/runs';$locks=Join-Path $runs '.locks'
    [IO.Directory]::CreateDirectory($locks)|Out-Null
    $id=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,12)
    $path=Join-Path $runs $id
    $null=Assert-ArtifactPath $rootPath $path
    [IO.Directory]::CreateDirectory($path)|Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $path 'logs'))|Out-Null
    $lock=[IO.File]::Open((Join-Path $locks ($id+'.lock')),'OpenOrCreate','ReadWrite','None')
    $self=Get-Process -Id $PID
    $manifest=[pscustomobject]@{schema=1;managed=$true;id=$id;root=$rootPath;path=$path;kind=$Kind;version=[string](Get-ArtifactVersion $rootPath);createdAt=(Get-Date).ToString('o');ownerId=$PID;ownerTicks=$self.StartTime.ToUniversalTime().Ticks.ToString();keep=[bool]$KeepArtifacts;status='running';cleanup='pending';reason='';reclaimedBytes=0}
    Write-ArtifactJson (Join-Path $path 'run.json') $manifest
    return [pscustomobject]@{root=$rootPath;path=$path;id=$id;manifest=$manifest;lock=$lock}
}
function Read-ArtifactContext([string]$Root,[string]$ContextPath){
    $path=Assert-ArtifactPath (Join-Path $Root '.release-work/runs') $ContextPath
    $m=Get-Content -LiteralPath $path -Raw -Encoding UTF8|ConvertFrom-Json
    if($m.schema -ne 1 -or -not $m.managed -or $m.root -ne [IO.Path]::GetFullPath($Root).TrimEnd('\') -or $m.path -ne [IO.Path]::GetDirectoryName($path) -or $m.id -ne [IO.Path]::GetFileName($m.path)){throw 'Invalid task ownership manifest'}
    return [pscustomobject]@{root=$m.root;path=$m.path;id=$m.id;manifest=$m;lock=$null}
}
function Register-ArtifactInstance($Run,[string]$Fixture){
    $full=Assert-ArtifactPath $Run.path $Fixture
    Write-ArtifactJson (Join-Path $Run.path ('instance-'+[guid]::NewGuid().ToString('N')+'.json')) @{fixture=$full}
}
function Register-ArtifactProcess($Run,$Process,[string]$Purpose){
    Write-ArtifactJson (Join-Path $Run.path ('process-'+$Process.Id+'.json')) @{id=$Process.Id;ticks=$Process.StartTime.ToUniversalTime().Ticks.ToString();exe=$Process.Path;purpose=$Purpose}
}
function Test-ArtifactProcess($Identity){
    $p=Get-Process -Id ([int]$Identity.id) -ErrorAction SilentlyContinue
    if(-not $p){return $null}
    if($p.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$Identity.ticks){return $null}
    if(-not $p.Path -or -not [string]::Equals($p.Path,[string]$Identity.exe,[StringComparison]::OrdinalIgnoreCase)){throw 'Cannot verify owned process executable'}
    return $p
}
function Stop-ArtifactInstances($Run){
    foreach($record in (Get-ChildItem -LiteralPath $Run.path -Filter 'instance-*.json' -File)){
        $fixture=(Get-Content -LiteralPath $record.FullName -Raw -Encoding UTF8|ConvertFrom-Json).fixture
        $null=Assert-ArtifactPath $Run.path $fixture
        if(-not(Test-Path -LiteralPath $fixture)){continue}
        foreach($mode in @('server','portable','dev')){
            $modeState=Join-Path $fixture ("data/"+$mode+"/instance-upgrade.json")
            if(Test-Path -LiteralPath $modeState){
                $saved=Get-Content -LiteralPath $modeState -Raw -Encoding UTF8|ConvertFrom-Json
                if(-not $saved.app -and -not $saved.database -and -not $saved.nativeDrive){continue}
                $null=& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Join-Path $fixture 'server/stop-server.ps1') -Mode $mode
                if($LASTEXITCODE -ne 0){throw "Instance stop failed: $fixture ($mode)"}
                $saved=Get-Content -LiteralPath $modeState -Raw -Encoding UTF8|ConvertFrom-Json
                if($saved.app -or $saved.database -or $saved.nativeDrive){throw "Instance remains active: $fixture ($mode)"}
            }
        }
        $stateFile=Join-Path $fixture 'data/instance-upgrade.json'
        if(Test-Path -LiteralPath $stateFile){
            $null=& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Join-Path $fixture 'launcher/stop-ems.ps1')
            if($LASTEXITCODE -ne 0){throw "Instance stop failed: $fixture"}
            $state=Get-Content -LiteralPath $stateFile -Raw -Encoding UTF8|ConvertFrom-Json
            if($state.app -or $state.database -or $state.nativeDrive){throw "Instance still has process records: $fixture"}
        }
    }
    foreach($record in (Get-ChildItem -LiteralPath $Run.path -Filter 'process-*.json' -File)){
        $identity=Get-Content -LiteralPath $record.FullName -Raw -Encoding UTF8|ConvertFrom-Json
        $process=Test-ArtifactProcess $identity
        if($process){
            if($identity.purpose -ne 'developer-main'){throw 'Owned browser has not exited; retaining its workspace'}
            $process.Kill();if(-not $process.WaitForExit(10000)){throw 'Developer test process did not exit'}
        }
    }
}
function Assert-RunOwnership($Run){
    $expected=Join-Path (Join-Path $Run.root '.release-work/runs') $Run.id
    $path=Assert-ArtifactPath (Join-Path $Run.root '.release-work/runs') $Run.path
    if($path -ne $expected -or -not $Run.lock -or $Run.manifest.ownerId -ne $PID -or $Run.manifest.ownerTicks -ne (Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString()){throw 'Run does not belong to this caller'}
    $disk=Read-ArtifactContext $Run.root (Join-Path $path 'run.json')
    if($disk.manifest.ownerTicks -ne $Run.manifest.ownerTicks -or $disk.manifest.ownerId -ne $PID){throw 'Run owner was changed'}
    $null=@(Get-ArtifactFiles $path -Strict)
    return $path
}
function Remove-ArtifactRun($Run){
    $path=Assert-RunOwnership $Run
    $null=Assert-ArtifactPath $Run.root $path
    # Hold the run lock outside its directory until removal is complete.
    $bytes=Get-ArtifactBytes $path
    for($attempt=0;$attempt -lt 3;$attempt++){
        try{Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction Stop;break}
        catch{if($attempt -ge 2){throw};Start-Sleep -Milliseconds 750}
    }
    return $bytes
}
function Protect-ArtifactText([string]$Text){
    $text=[regex]::Replace($Text,'(?im)(password|passwd|token|cookie|authorization|X-EMS-Token)\s*[:=]\s*[^\r\n,;]+','$1=[REDACTED]')
    return $text
}
function Save-ArtifactEvidence($Run){
    $dest=Join-Path (Join-Path $Run.root '.release-evidence') ($Run.manifest.version+'/'+$Run.id)
    $null=Assert-ArtifactPath $Run.root $dest
    [IO.Directory]::CreateDirectory($dest)|Out-Null
    $budget=10MB-64KB
    $candidates=@()
    $logs=Join-Path $Run.path 'logs'
    if(Test-Path -LiteralPath $logs){$candidates+=@(Get-ArtifactFiles $logs|Where-Object{$_.Extension -in @('.log','.txt')})}
    $candidates+=@(Get-ChildItem -LiteralPath $Run.path -File|Where-Object{$_.Name -match '(results|summary)\.(txt|json)$'})
    $screens=@(Get-ChildItem -LiteralPath $Run.path -Filter '*.png' -File|Sort-Object LastWriteTime -Descending|Select-Object -First 1)
    $candidates+=$screens
    $omitted=New-Object Collections.Generic.List[string]
    foreach($file in $candidates){
        if($budget -lt 4096){$omitted.Add($file.Name);continue}
        $out=Join-Path $dest $file.Name
        if($file.Extension -eq '.png'){
            if($file.Length -gt [Math]::Min(2MB,$budget)){$omitted.Add($file.Name);continue}
            Copy-Item -LiteralPath $file.FullName -Destination $out -Force;$budget-=$file.Length
        }else{
            $limit=[int][Math]::Min(2MB,$budget)-512
            $stream=[IO.File]::OpenRead($file.FullName)
            try{$length=[int][Math]::Min($limit,$stream.Length);$null=$stream.Seek(-$length,[IO.SeekOrigin]::End);$buffer=New-Object byte[] $length;$null=$stream.Read($buffer,0,$length)}finally{$stream.Dispose()}
            $text=Protect-ArtifactText (Read-ArtifactTail $file.FullName $limit)
            $encoded=[Text.Encoding]::UTF8.GetBytes($text)
            if($encoded.Length -gt $limit){$text=[Text.Encoding]::UTF8.GetString($encoded,($encoded.Length-$limit),$limit)}
            if($file.Length -gt $limit){$text="[Earlier log bytes truncated]"+[Environment]::NewLine+$text}
            [IO.File]::WriteAllText($out,$text,(New-Object Text.UTF8Encoding($false)));$budget-=(Get-Item -LiteralPath $out).Length
        }
    }
    $record=$Run.manifest
    $record|Add-Member -NotePropertyName omitted -NotePropertyValue @($omitted) -Force
    Write-ArtifactJson (Join-Path $dest 'evidence.json') $record
    return $dest
}
function Limit-ArtifactEvidence([string]$Root){
    $base=Join-Path $Root '.release-evidence'
    [IO.Directory]::CreateDirectory((Join-Path $Root '.release-work'))|Out-Null
    $maintenance=[IO.File]::Open((Join-Path $Root '.release-work/evidence-maintenance.lock'),'OpenOrCreate','ReadWrite','None')
    try{
    $items=@(Get-ArtifactFiles $base|Where-Object{$_.Name -eq 'evidence.json'}|ForEach-Object{
        $m=Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8|ConvertFrom-Json
        if($m.schema -eq 1 -and $m.managed -and $m.root -eq $Root -and $m.id -eq $_.Directory.Name -and $m.path -eq (Join-Path $Root ('.release-work/runs/'+$m.id)) -and -not $m.keep -and $m.status -in @('succeeded','failed')){[pscustomobject]@{path=$_.DirectoryName;time=$m.createdAt}}
    }|Sort-Object time)
    $count=$items.Count
    foreach($entry in $items){
        if($count -le 20 -and (Get-ArtifactBytes $base) -le 200MB){break}
        $target=Assert-ArtifactPath $base $entry.path
        $null=@(Get-ArtifactFiles $target -Strict)
        Remove-Item -LiteralPath $target -Recurse -Force;$count--
    }
    if((Get-ArtifactBytes $base) -gt 200MB){throw 'Pinned or unmanaged evidence exceeds the automatic retention budget'}
    }finally{$maintenance.Dispose()}
}
function Complete-ArtifactRun {
    [CmdletBinding()]param($Run,[bool]$Succeeded,[string]$OriginalError='')
    $Run.manifest.status=if($Succeeded){'succeeded'}else{'failed'}
    $code=if($Succeeded){0}else{1}
    $Run.manifest.reason=Protect-ArtifactText $OriginalError
    try{
        $null=Assert-RunOwnership $Run
        Stop-ArtifactInstances $Run
        $Run.manifest.cleanup=if($Run.manifest.keep){'kept-by-request'}else{'ready'}
        Write-ArtifactJson (Join-Path $Run.path 'run.json') $Run.manifest
        $evidence=Save-ArtifactEvidence $Run
        if(-not $Run.manifest.keep){
            $Run.manifest.reclaimedBytes=Remove-ArtifactRun $Run
            $Run.manifest.cleanup='removed'
        }
    }catch{
        $Run.manifest.cleanup='blocked';$Run.manifest.reason+=('; cleanup: '+$_.Exception.Message)
        if($Succeeded){$code=2}
        Write-Warning $Run.manifest.reason
        try{$evidence=Save-ArtifactEvidence $Run}catch{Write-Warning "Evidence persistence failed: $($_.Exception.Message)"}
    }finally{
        if($Run.lock){$Run.lock.Dispose();$Run.lock=$null}
        $lockFile=Join-Path $Run.root ('.release-work/runs/.locks/'+$Run.id+'.lock')
        if(Test-Path -LiteralPath $lockFile){try{Remove-Item -LiteralPath $lockFile -Force}catch{}}
    }
    if($evidence){Write-ArtifactJson (Join-Path $evidence 'evidence.json') $Run.manifest}
    if(Test-Path -LiteralPath $Run.path){Write-ArtifactJson (Join-Path $Run.path 'run.json') $Run.manifest}
    try{Limit-ArtifactEvidence $Run.root}catch{Write-Warning $_.Exception.Message;if($code -eq 0){$code=2}}
    Write-Host ("Task {0}: {1}; cleanup={2}; reclaimed={3} bytes; evidence={4}" -f $Run.id,$Run.manifest.status,$Run.manifest.cleanup,$Run.manifest.reclaimedBytes,$evidence)
    return $code
}
function Resolve-ArtifactTool([string]$Explicit,[string]$Name){
    if($Explicit){if(-not(Test-Path -LiteralPath $Explicit -PathType Leaf)){throw "Tool not found: $Explicit"};return [IO.Path]::GetFullPath($Explicit)}
    $command=Get-Command $Name -ErrorAction SilentlyContinue
    if($command){return $command.Source}
    throw "Required development tool missing: $Name; supply its explicit path."
}

function Invoke-ArtifactNative([string]$Exe,[string[]]$Arguments,[string]$LogFile){
    $ErrorActionPreference="Continue"
    if([IO.Path]::GetFileName($Exe) -ieq 'powershell.exe'){
        $out=$LogFile+'.stdout';$err=$LogFile+'.stderr'
        $quoted=@($Arguments|ForEach-Object {if($_.Contains('"')){throw 'Embedded executable quotes refused'};'"'+$_+'"'}) -join ' '
        $p=Start-Process -FilePath $Exe -ArgumentList $quoted -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
        try{
            # Wait only for the launcher, never for its deliberately detached MySQL/JVM descendants.
            $null=$p.Handle;$p.WaitForExit();$code=$p.ExitCode;if($null -eq $code){throw 'Launcher exit code unavailable'}
            $text=''
            foreach($file in @($out,$err)){if(Test-Path -LiteralPath $file){$stream=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete));$reader=New-Object IO.StreamReader($stream);try{$text+=$reader.ReadToEnd()+[Environment]::NewLine}finally{$reader.Dispose()}}}
            [IO.File]::WriteAllText($LogFile,$text,(New-Object Text.UTF8Encoding($false)))
            Remove-Item -LiteralPath $out,$err -Force -ErrorAction SilentlyContinue
            return [int]$code
        }finally{$p.Dispose()}
    }
    & $Exe @Arguments 2>&1 | Out-File -LiteralPath $LogFile -Encoding UTF8 -Width 240
    return [int]$LASTEXITCODE
}

function Read-ArtifactTail([string]$Path,[int]$Limit=131072){
    $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try{
        $header=New-Object byte[] 4;$null=$stream.Read($header,0,4)
        $encoding=if($header[0] -eq 255 -and $header[1] -eq 254){[Text.Encoding]::Unicode}else{[Text.Encoding]::UTF8}
        $length=[int][Math]::Min($Limit,$stream.Length);$offset=$stream.Length-$length
        if($encoding -eq [Text.Encoding]::Unicode -and $offset%2){$offset++;$length--}
        $null=$stream.Seek($offset,[IO.SeekOrigin]::Begin);$buffer=New-Object byte[] $length
        $null=$stream.Read($buffer,0,$length)
        return $encoding.GetString($buffer).TrimStart([char]0xFEFF)
    }finally{$stream.Dispose()}
}

function Recover-ArtifactRun([string]$Root,[string]$ContextPath){
    $run=Read-ArtifactContext $Root $ContextPath
    $owner=Get-Process -Id $run.manifest.ownerId -ErrorAction SilentlyContinue
    $same=$owner -and $owner.StartTime.ToUniversalTime().Ticks.ToString() -eq $run.manifest.ownerTicks
    if($same -and ($owner.Id -ne $PID -or $run.manifest.status -eq 'running')){throw 'Task owner is still active; recovery refused'}
    $file=Join-Path $Root ('.release-work/runs/.locks/'+$run.id+'.lock')
    $run.lock=[IO.File]::Open($file,'OpenOrCreate','ReadWrite','None')
    try{
        $null=@(Get-ArtifactFiles $run.path -Strict)
        $previous=@{id=$run.manifest.ownerId;ticks=$run.manifest.ownerTicks}
        $run.manifest|Add-Member -NotePropertyName previousOwner -NotePropertyValue $previous -Force
        $run.manifest.ownerId=$PID
        $run.manifest.ownerTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString()
        Write-ArtifactJson (Join-Path $run.path 'run.json') $run.manifest
        return $run
    }catch{$run.lock.Dispose();throw}
}
