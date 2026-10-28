param([switch]$RequireTarget)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\artifact-common.ps1"
$baseline=Get-Content -LiteralPath (Join-Path $root '.release-evidence/v1.1.3/baseline/baseline.json') -Raw -Encoding UTF8|ConvertFrom-Json
$out=Join-Path $root '空间审计报告_v1.1.3.json'
$markdown=Join-Path $root '空间审计报告_v1.1.3.md'
for($pass=0;$pass -lt 3;$pass++){
    $files=@(Get-ArtifactFiles $root -Strict)
    $bytes=[long](($files|Measure-Object Length -Sum).Sum)
    $reduction=[long]$baseline.bytes-$bytes
    $percent=[Math]::Round(100.0*$reduction/[double]$baseline.bytes,4)
    $directories=@(Get-ChildItem -LiteralPath $root -Directory -Force|ForEach-Object{@{name=$_.Name;bytes=(Get-ArtifactBytes $_.FullName)}})
    $historical=0L
    $legacy=Join-Path $root '.release-evidence/legacy-v1.1.2/historical-cleanup.json'
    if(Test-Path -LiteralPath $legacy){$legacyRecord=Get-Content -LiteralPath $legacy -Raw -Encoding UTF8|ConvertFrom-Json;$historical=if($legacyRecord.historicalReclaimedBytes){[long]$legacyRecord.historicalReclaimedBytes}else{[long]$legacyRecord.reclaimedBytes}}
    $evidenceRecords=@(Get-ArtifactFiles (Join-Path $root '.release-evidence')|Where-Object{$_.Name -eq 'evidence.json'}|ForEach-Object{Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8|ConvertFrom-Json})
    $newReclaimed=[long](($evidenceRecords|Measure-Object reclaimedBytes -Sum).Sum)
    $running=@()
    $runs=Join-Path $root '.release-work/runs'
    if(Test-Path -LiteralPath $runs){$running=@(Get-ChildItem -LiteralPath $runs -Directory|Where-Object{$_.Name -ne '.locks'}|ForEach-Object{
        $manifest=Join-Path $_.FullName 'run.json'
        if(Test-Path -LiteralPath $manifest){
            $m=Get-Content -LiteralPath $manifest -Raw -Encoding UTF8|ConvertFrom-Json
            $owner=Get-Process -Id $m.ownerId -ErrorAction SilentlyContinue
            $alive=$owner -and $owner.StartTime.ToUniversalTime().Ticks.ToString() -eq $m.ownerTicks
            @{name=$_.Name;status=$(if($m.status -eq 'running' -and -not $alive){'interrupted'}else{$m.status});cleanup=$m.cleanup;keep=$m.keep;ownerAlive=[bool]$alive;bytes=(Get-ArtifactBytes $_.FullName)}
        }else{@{name=$_.Name;status='unknown';bytes=(Get-ArtifactBytes $_.FullName)}}
    })}
    $preserved=@(foreach($item in $baseline.protected){
        $path=Join-Path $root $item.relative
        if(-not(Test-Path -LiteralPath $path)){throw "Protected file missing: $($item.relative)"}
        if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $item.sha256){throw "Protected file changed: $($item.relative)"}
        $item.relative
    })
    $record=[ordered]@{
        recordedAt=(Get-Date).ToString('o');baselineBytes=[long]$baseline.bytes;finalBytes=$bytes;reducedBytes=$reduction;reductionPercent=$percent;
        maxFinalBytes=[long](1.5GB);minimumReductionPercent=60;spacePassed=($bytes -le 1.5GB -and (100.0*$reduction/[double]$baseline.bytes) -ge 60);
        freeSpaceChangeBytes=([IO.DriveInfo]::new([IO.Path]::GetPathRoot($root)).AvailableFreeSpace-[long]$baseline.freeBytes);
        historicalReclaimedBytes=$historical;newTaskReclaimedBytes=$newReclaimed;directories=$directories;
        largestFiles=@($files|Sort-Object Length -Descending|Select-Object -First 20|ForEach-Object{@{path=$_.FullName.Substring($root.Length+1);bytes=$_.Length}});
        retainedTasks=$running;preservedHistoricalFiles=$preserved;
        releases=@(Get-ChildItem -LiteralPath (Join-Path $root 'dist') -Filter '*.zip'|ForEach-Object{@{file=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}});
        note='Logical file lengths include all local project directories. Free-space change is measured separately and can be affected by other programs.'
    }
    Write-ArtifactJson $out $record
    $lines=@('# EMS v1.1.3 空间审计报告','',
        '| 指标 | 结果 |','|---|---|',
        ("| v1.1.2冻结基线 | {0:N0} 字节（{1:N3} GiB） |" -f $baseline.bytes,($baseline.bytes/1GB)),
        ("| v1.1.3完整工程 | {0:N0} 字节（{1:N3} GiB） |" -f $bytes,($bytes/1GB)),
        ("| 净减少 | {0:N0} 字节（{1:N2}%） |" -f $reduction,$percent),
        ("| 空间目标 | {0}：不超过1.5 GiB，且减少至少60% |" -f $(if($record.spacePassed){'通过'}else{'未通过'})),
        ("| 历史临时文件删除量 | {0:N0} 字节 |" -f $historical),
        ("| 保留任务证据对应的末尾回收量 | {0:N0} 字节；只统计仍保留的任务证据，且不含阶段提前回收；不等同于净减少量 |" -f $newReclaimed),
        ("| 磁盘可用空间变化 | {0:N0} 字节；受其他进程影响 |" -f $record.freeSpaceChangeBytes),'',
        '统计包含全部发行包、运行库、真实data、源码、缓存、归档JAR和精简证据；未通过转移到工程外或目录链接缩小统计范围。','',
        '旧发行ZIP及校验文件、历史SQL备份和原归档JAR均通过SHA256保留核对。真实data与运行库仍保留。','',
        '## 一级目录','| 目录 | 字节 |','|---|---|')
    foreach($dir in ($directories|Sort-Object bytes -Descending)){$lines+=("| {0} | {1:N0} |" -f $dir.name,$dir.bytes)}
    $lines+=@('','## 保留任务',$(if($running.Count){($running|ConvertTo-Json -Depth 4)}else{'没有遗留完整任务副本。'}),'','详细文件清单与发行包校验见同名JSON；历史清理逐项记录见.release-evidence/legacy-v1.1.2/historical-cleanup.json。')
    [IO.File]::WriteAllText($markdown,($lines -join [Environment]::NewLine),(New-Object Text.UTF8Encoding($false)))
}
Write-Host ("Baseline={0}; final={1}; reduced={2:N2}%; passed={3}" -f $record.baselineBytes,$record.finalBytes,$record.reductionPercent,$record.spacePassed)
if($RequireTarget -and (-not $record.spacePassed -or $running.Count)){exit 1}
