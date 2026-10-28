param([ValidateSet('server','portable','dev')][string]$Mode='server',[int]$Interval=2,[switch]$Once,[switch]$NoClear)
$ErrorActionPreference='Stop'
if($Interval -lt 1 -or $Interval -gt 60){throw '刷新间隔须为1～60秒'}
$root=Split-Path -Parent $PSScriptRoot
$data=Join-Path $root "data\$Mode"
$logs=Join-Path $root "logs\$Mode"
$stateFile=Join-Path $data 'instance-upgrade.json'
$monitorLog=Join-Path $logs 'monitor.log'
$utf8=New-Object System.Text.UTF8Encoding($false)
$lastSignature=''
$lastCpu=@{}
function Get-VerifiedProcess($identity,$expected){
 if(-not $identity){return $null}
 try{
  $p=Get-Process -Id ([int]$identity.id) -ErrorAction Stop
  if($p.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$identity.ticks){return $null}
  if(-not [string]::Equals([IO.Path]::GetFullPath([string]$identity.exe),$expected,[StringComparison]::OrdinalIgnoreCase)){return $null}
  $actual=[string]$p.Path
  if(-not ([string]::Equals($actual,$expected,[StringComparison]::OrdinalIgnoreCase) -or ($identity.nativeExe -and [string]::Equals($actual,[string]$identity.nativeExe,[StringComparison]::OrdinalIgnoreCase)))){return $null}
  return $p
 }catch{return $null}
}
function Get-Health($address,$port){
 if(-not $address -or -not $port){return '未配置'}
 try{
  $req=[Net.HttpWebRequest]::Create("http://${address}:${port}/health")
  $req.Proxy=$null;$req.Timeout=1500;$req.ReadWriteTimeout=1500
  $res=$req.GetResponse()
  try{
   $reader=New-Object IO.StreamReader($res.GetResponseStream())
   try{$body=$reader.ReadToEnd()|ConvertFrom-Json; if($body.status -eq 'UP'){return 'UP (200)'};return '异常响应'}finally{$reader.Dispose()}
  }finally{$res.Dispose()}
 }catch{return '不可达'}
}
function Get-Stats($p,$key){
 if(-not $p){return '未运行'}
 try{
  $p.Refresh();$now=Get-Date;$cpu=[double]$p.CPU
  $usage='采样中'
  if($lastCpu.ContainsKey($key)){
   $prev=$lastCpu[$key];$sec=($now-$prev.time).TotalSeconds
   if($sec -gt 0){$usage=('{0:N1}%' -f (100*($cpu-$prev.cpu)/$sec/[Environment]::ProcessorCount))}
  }
  $lastCpu[$key]=@{time=$now;cpu=$cpu}
  $mem=[math]::Round($p.WorkingSet64/1MB,1)
  $uptime=($now-$p.StartTime).ToString('dd\.hh\:mm\:ss')
  return "PID $($p.Id) | CPU $usage | 内存 $mem MB | 运行 $uptime"
 }catch{return '进程信息暂不可用'}
}
function Render {
 $s=$null;$readError=''
 if(Test-Path -LiteralPath $stateFile){
  try{$s=Get-Content -LiteralPath $stateFile -Raw -Encoding UTF8|ConvertFrom-Json;if($s.version -ne '1.2.0' -or $s.mode -ne $Mode){throw '实例版本或模式不匹配'}}catch{$s=$null;$readError=$_.Exception.Message}
 }
 $java=Join-Path $root 'runtime\java\bin\java.exe'
 $mysql=Join-Path $root 'runtime\mysql\bin\mysqld.exe'
 $app=$null;$db=$null;$health='未初始化';$connections='--';$addr='--';$port='--'
 if($s){
  $app=Get-VerifiedProcess $s.app $java;$db=Get-VerifiedProcess $s.database $mysql
  $addr=[string]$s.address;$port=[string]$s.httpPort
  $health=Get-Health $addr $port
  try{$connections=@(Get-NetTCPConnection -LocalAddress $addr -LocalPort ([int]$port) -State Established -ErrorAction Stop).Count}catch{$connections='无法获取'}
 }
 $appState=if($app){'运行中'}else{'已停止'}
 $dbState=if($db){'运行中'}else{'已停止'}
 $overall=if($app -and $db -and $health -eq 'UP (200)'){'正常'}elseif(-not $s){'未初始化'}else{'异常或已停止'}
 $signature="$overall|$appState|$dbState|$health"
 if($signature -ne $script:lastSignature){
  try{
   [IO.Directory]::CreateDirectory($logs)|Out-Null
   $line='{0} [{1}] Java={2}; MySQL={3}; HTTP={4}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'),$overall,$appState,$dbState,$health
   [IO.File]::AppendAllText($monitorLog,$line+[Environment]::NewLine,$utf8)
  }catch{}
  $script:lastSignature=$signature
 }
 if(-not $NoClear){Clear-Host}
 Write-Host '============================================='
 Write-Host ' EMS v1.2.0 实时服务器监控（只读）'
 Write-Host '============================================='
 Write-Host "时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  模式：$Mode  刷新：$Interval 秒"
 Write-Host "总体状态：$overall"
 Write-Host "访问地址：http://${addr}:${port}"
 Write-Host "HTTP /health：$health"
 Write-Host "Java：$appState  $(Get-Stats $app 'app')"
 Write-Host "MySQL：$dbState  $(Get-Stats $db 'db')"
 Write-Host "已建立 TCP 连接数：$connections（非在线用户数）"
 Write-Host '---------------------------------------------'
 if($readError){Write-Host "实例状态读取失败：$readError"}
 $errorLog=Join-Path $logs 'application-error.log'
 if(Test-Path -LiteralPath $errorLog){
  Write-Host '应用错误日志末尾（最多5行）：'
  try{Get-Content -LiteralPath $errorLog -Tail 5 -Encoding UTF8 -ErrorAction Stop|ForEach-Object{Write-Host $_}}catch{Write-Host '日志暂不可读取'}
 }else{Write-Host '应用错误日志：暂无'}
 Write-Host '---------------------------------------------'
 Write-Host '按 Ctrl+C 退出监控；不会停止服务器。'
}
do{
 try{Render}catch{Write-Host ("监控采样异常："+$_.Exception.Message)}
 if($Once){break}
 Start-Sleep -Seconds $Interval
}while($true)
