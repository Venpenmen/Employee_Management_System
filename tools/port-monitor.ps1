param(
    [int]$Interval = 2,
    [switch]$Once,
    [switch]$NoClear,
    [string]$InstanceRoot = ''
)
$ErrorActionPreference = 'Stop'
if($Interval -lt 1 -or $Interval -gt 30){ throw '刷新间隔须为 1～30 秒' }

function Resolve-Root([string]$given){
    if($given){
        if(-not (Test-Path -LiteralPath $given)){ throw "实例目录不存在：$given" }
        return [IO.Path]::GetFullPath($given)
    }
    $dir = $PSScriptRoot
    for($i = 0; $i -lt 4 -and $dir; $i++){
        if(Test-Path -LiteralPath (Join-Path $dir 'app\version-complete-rc2.json')){ return $dir }
        if(Test-Path -LiteralPath (Join-Path $dir 'data\complete-rc2')){ return $dir }
        $dir = Split-Path -Parent $dir
    }
    throw '未找到 RC2 实例目录，请用 -InstanceRoot 指定解压目录'
}

function Read-Json([string]$path){
    if(-not (Test-Path -LiteralPath $path)){ return $null }
    try{ return [IO.File]::ReadAllText($path,[Text.Encoding]::UTF8) | ConvertFrom-Json }catch{ return $null }
}

function Get-Listeners([int]$port){
    try{
        return @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop)
    }catch{
        $rows = @()
        foreach($line in (netstat -ano -p TCP | Select-String -Pattern (":$port\s+.*LISTENING" ))){
            $parts = ($line.Line -split '\s+') | Where-Object { $_ }
            if($parts.Count -ge 5){
                $rows += [pscustomobject]@{ LocalAddress = ($parts[1] -split ':')[0]; LocalPort = $port; OwningProcess = [int]$parts[-1] }
            }
        }
        return $rows
    }
}

function Get-ProcessLabel([int]$processId){
    try{
        $p = Get-Process -Id $processId -ErrorAction Stop
        $started = ''
        try{ $started = $p.StartTime.ToString('MM-dd HH:mm:ss') }catch{ $started = '启动时间不可读' }
        $path = ''
        try{ $path = [string]$p.Path }catch{ $path = '' }
        return [pscustomobject]@{ Name = $p.ProcessName; Id = $p.Id; Started = $started; Path = $path; Ref = $p }
    }catch{
        return [pscustomobject]@{ Name = '未知'; Id = $processId; Started = '无权限读取'; Path = ''; Ref = $null }
    }
}

function Get-Health([string]$address,[int]$port){
    try{
        $req = [Net.HttpWebRequest]::Create("http://${address}:${port}/health")
        $req.Proxy = $null; $req.Timeout = 1500; $req.ReadWriteTimeout = 1500
        $res = $req.GetResponse()
        try{
            $reader = New-Object IO.StreamReader($res.GetResponseStream())
            try{ $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if($body -match '"status"\s*:\s*"UP"'){ return 'UP (200)' }
            return ("响应异常：" + ($body.Trim()))
        }finally{ $res.Dispose() }
    }catch{
        return '不可达'
    }
}

function Read-SubstTable{
    try{
        $psi = New-Object Diagnostics.ProcessStartInfo
        $psi.FileName = $env:ComSpec; $psi.Arguments = '/c subst'
        $psi.UseShellExecute = $false; $psi.RedirectStandardOutput = $true; $psi.CreateNoWindow = $true
        $psi.StandardOutputEncoding = [Text.Encoding]::GetEncoding([Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
        $proc = [Diagnostics.Process]::Start($psi)
        $out = $proc.StandardOutput.ReadToEnd(); $proc.WaitForExit()
        $rows = @()
        foreach($line in ($out -split "`r?`n")){
            $m = [regex]::Match($line, '^([A-Z]:)\\: => (.*)$')
            if($m.Success){ $rows += [pscustomobject]@{ Drive = $m.Groups[1].Value; Target = $m.Groups[2].Value.Trim() } }
        }
        return $rows
    }catch{ return @() }
}

function Get-SubstDrive([string]$root,[string]$nativeDrive){
    if($null -eq $script:substCache){ $script:substCache = Read-SubstTable }
    if($nativeDrive -and -not (@($script:substCache) | Where-Object { $_.Drive -eq $nativeDrive })){
        $script:substCache = Read-SubstTable
    }
    $mine = @($script:substCache | Where-Object { $_.Target -and $root -and $_.Target.TrimEnd('\') -ieq $root.TrimEnd('\') })
    $others = @($script:substCache | Where-Object { -not ($mine -contains $_) })
    $parts = @()
    if($mine.Count -gt 0){ $parts += (($mine | ForEach-Object { "$($_.Drive)\ => $($_.Target)" }) -join '; ') } else { $parts += '本实例未挂载原生盘' }
    if($others.Count -gt 0){ $parts += ("另有 {0} 个其它映射（{1}）" -f $others.Count, (($others | ForEach-Object { $_.Drive }) -join ',')) }
    return ($parts -join '；')
}

function Get-ConnectionCount([int]$port){
    try{
        return @(Get-NetTCPConnection -LocalPort $port -State Established -ErrorAction Stop).Count
    }catch{
        try{
            return @(Get-NetTCPConnection -LocalPort $port -ErrorAction Stop | Where-Object { $_.State -eq 'Established' }).Count
        }catch{ return '无法获取' }
    }
}

$root = $null
try{ $root = Resolve-Root $InstanceRoot }
catch{
    $root = if($InstanceRoot){ $InstanceRoot } else { Split-Path -Parent $PSScriptRoot }
    $script:rootWarning = '未找到已初始化的 RC2 实例，仅显示端口占用总览（可用 -InstanceRoot 指定解压目录）'
}
$mode = 'server'
$stateFile = Join-Path $root "data\complete-rc2\$mode\instance-upgrade.json"
$runtimeFile = Join-Path $root "data\complete-rc2\$mode\network-runtime.json"
$watchBand = @(8481,8482,8483,8484,8485) + (16606..16630)

function Render{
    $now = Get-Date
    $state = Read-Json $stateFile
    $runtime = Read-Json $runtimeFile

    $httpPort = 0; $httpsPort = 0; $dbPort = 0; $address = '127.0.0.1'; $nativeDrive = ''
    $appPid = 0; $dbPid = 0
    if($state){
        $httpPort = [int]$state.httpPort; $dbPort = [int]$state.dbPort
        $address = [string]$state.address; $nativeDrive = [string]$state.nativeDrive
        $appPid = [int]$state.app.id; $dbPid = [int]$state.database.id
    }
    $generation = '未知'; $bootId = '未知'; $netMode = '未知'; $tls = $false
    if($runtime){
        $generation = [string]$runtime.generation; $netMode = [string]$runtime.mode
        $bootId = [string]$runtime.bootId; if($runtime.httpsPort){ $httpsPort = [int]$runtime.httpsPort }
        if($runtime.httpsEnabled){ $tls = [bool]$runtime.httpsEnabled }
    }

    if(-not $NoClear){ Clear-Host }
    Write-Host '==============================================================' -ForegroundColor Cyan
    Write-Host ' EMS v1.2.1 实时端口监控（只读，不改配置、不启停服务）' -ForegroundColor Cyan
    Write-Host '==============================================================' -ForegroundColor Cyan
    Write-Host ("时间：{0}    刷新：{1} 秒" -f $now.ToString('yyyy-MM-dd HH:mm:ss'), $Interval)
    Write-Host ("目录：{0}" -f $root)
    if($script:rootWarning){ Write-Host $script:rootWarning -ForegroundColor Yellow }
    if(-not $state){
        Write-Host '实例状态：未初始化（找不到 instance-upgrade.json，或该目录从未完成初始化）' -ForegroundColor Yellow
    }else{
        Write-Host ("实例状态：mode={0}  网络模式={1}  代次={2}  bootId={3}" -f $state.mode, $netMode, $generation, ($(if($bootId.Length -gt 8){ $bootId.Substring(0,8) } else { $bootId })))
        Write-Host ("访问入口：http://{0}:{1}    原生临时盘：{2}" -f $address, $httpPort, $(if($nativeDrive){$nativeDrive}else{'无'}))
    }
    Write-Host '--------------------------------------------------------------'

    $expected = @()
    if($httpPort -gt 0){ $expected += [pscustomobject]@{ Port = $httpPort; Role = 'HTTP 入口'; Owner = $appPid } }
    if($httpsPort -gt 0){ $expected += [pscustomobject]@{ Port = $httpsPort; Role = 'HTTPS 入口'; Owner = $appPid } }
    if($dbPort -gt 0){ $expected += [pscustomobject]@{ Port = $dbPort; Role = 'MySQL'; Owner = $dbPid } }

    Write-Host '预期端口：' -ForegroundColor Gray
    foreach($item in $expected){
        $listeners = @(Get-Listeners $item.Port)
        if($listeners.Count -eq 0){
            Write-Host ("  {0,-6} {1,-12} 未监听" -f $item.Port, $item.Role) -ForegroundColor Yellow
            continue
        }
        foreach($l in $listeners){
            $proc = Get-ProcessLabel ([int]$l.OwningProcess)
            $isOurs = ($item.Owner -gt 0 -and [int]$l.OwningProcess -eq $item.Owner)
            $tag = if($isOurs){ '本实例' } else { '其他进程' }
            $color = if($isOurs){ 'Green' } else { 'Red' }
            Write-Host ("  {0,-6} {1,-12} {2,-10} PID {3,-6} {4,-16} 启动 {5}" -f $item.Port, $item.Role, $tag, $proc.Id, $proc.Name, $proc.Started) -ForegroundColor $color
        }
    }

    Write-Host '--------------------------------------------------------------'
    Write-Host 'RC2 端口段扫描（8481-8485 / 16606-16630，用于发现残留实例）：' -ForegroundColor Gray
    $known = @($expected | ForEach-Object { $_.Port })
    $foreign = @()
    foreach($port in $watchBand){
        if($known -contains $port){ continue }
        foreach($l in @(Get-Listeners $port)){
            $proc = Get-ProcessLabel ([int]$l.OwningProcess)
            $hint = ''
            if($proc.Path -match '\\\.release-work\\|\\\.codex-continuation\\|\\test-work\\'){ $hint = '（测试夹具，非交付实例）' }
            $shown = $proc.Path
            if($shown){ $shown = ($shown -split '\\' | Select-Object -Last 3) -join '\' }
            $foreign += ("  端口 {0,-5} PID {1,-6} {2,-8} 启动 {3,-14} {4}{5}" -f $port, $proc.Id, $proc.Name, $proc.Started, $shown, $hint)
        }
    }
    if($foreign.Count -eq 0){
        Write-Host '  无占用（其他端口均空闲）' -ForegroundColor Gray
    }elseif(-not $state){
        foreach($line in $foreign){ Write-Host $line -ForegroundColor Gray }
        Write-Host '  说明：当前目录不是已初始化实例，以上仅列出占用情况，不做归属判断。' -ForegroundColor Gray
    }else{
        foreach($line in $foreign){ Write-Host $line -ForegroundColor Red }
        Write-Host '  提示：以上进程不属于当前实例，可能是别的候选副本或上次未退出的残留；先确认归属再处理。' -ForegroundColor Yellow
    }

    Write-Host '--------------------------------------------------------------'
    if($state -and $httpPort -gt 0){
        Write-Host ("HTTP /health：{0}    当前已建立连接：{1}" -f (Get-Health '127.0.0.1' $httpPort), (Get-ConnectionCount $httpPort))
    }
    Write-Host ("原生盘映射：{0}" -f (Get-SubstDrive $root $nativeDrive))
    Write-Host '--------------------------------------------------------------'
    Write-Host 'Ctrl+C 退出；本监控不修改任何配置，也不会停止服务器。' -ForegroundColor Gray
}

do{
    try{ Render }
    catch{ Write-Host ("采样异常：" + $_.Exception.Message) -ForegroundColor Red }
    if($Once){ break }
    Start-Sleep -Seconds $Interval
}while($true)
