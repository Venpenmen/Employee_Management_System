param([ValidateSet('server','portable','dev')][string]$Mode='server',[string]$DevJavaExe)
$ErrorActionPreference='Stop'
# A code must never be captured by a redirected launcher, test runner or evidence log.
if([Console]::IsOutputRedirected -or [Console]::IsInputRedirected -or $Host.Name -ne 'ConsoleHost'){throw 'Open this script directly in a local interactive Windows console; codes are never written to redirected output'}
. "$PSScriptRoot/runtime-common.ps1" -Mode $Mode
$lock=$null;$code=$null;$secret=$null;$part=$null
try{
 $lock=Enter-EmsLock;Assert-Package;$state=Read-EmsState
 if(-not $state -or -not(Get-OwnedProcess $state.database)){throw 'This candidate requires its own live database identity'}
 $file=Assert-ArtifactPath $script:DataDir (Join-Path $script:DataDir 'network-runtime.json')
 $runtime=Get-Content -LiteralPath $file -Raw -Encoding UTF8|ConvertFrom-Json
 if($runtime.buildId -ne 'v1.2.1-complete-rc2' -or ($state.app -and $runtime.generation -ne $state.networkGeneration) -or $runtime.bootId -notmatch '^[a-f0-9-]{36}$' -or $runtime.mode -ne 'safe'){throw 'Application boot/generation identity mismatch, or compatibility mode does not need a local code'}
 $secret=Read-EmsSecrets
 if($runtime.installRoot -ne $script:EmsRoot -or $runtime.runtimeMode -ne $Mode){throw 'Local application installation/mode mismatch'}
 $signed=([string]$runtime.pid)+'|'+$runtime.startTicks+'|'+$runtime.exe+'|'+$runtime.installRoot+'|'+$runtime.runtimeMode+'|'+$runtime.bootId+'|'+$runtime.generation
 $checkMac=New-Object Security.Cryptography.HMACSHA256;$checkMac.Key=[Text.Encoding]::UTF8.GetBytes($secret.control)
 try{$expectedMac=([BitConverter]::ToString($checkMac.ComputeHash([Text.Encoding]::UTF8.GetBytes($signed)))).Replace('-','').ToLowerInvariant()}finally{$checkMac.Dispose()}
 if($expectedMac -cne $runtime.identityMac){throw 'Application status authentication failed'}
 if($state.app){if($runtime.pid -ne $state.app.id -or -not(Get-OwnedProcess $state.app) -or -not(Test-EmsReady $state)){throw 'Owned launcher identity mismatch'}}
 else{
  if($Mode -ne 'dev' -or -not $DevJavaExe){throw 'IDEA direct main requires -Mode dev and the exact explicitly approved -DevJavaExe path'}
  $process=Get-Process -Id ([int]$runtime.pid) -ErrorAction Stop
  if([IO.Path]::GetFullPath($DevJavaExe) -ne $process.Path -or $process.Path -ne $runtime.exe -or [math]::Floor(([decimal]$process.StartTime.ToUniversalTime().Ticks)/10000) -ne [math]::Floor(([decimal]$runtime.startTicks)/10000)){throw 'IDEA process identity mismatch'}
  if([IO.File]::ReadAllText("$script:DataDir/ready").Trim() -ne [string]$runtime.pid){throw 'IDEA readiness identity mismatch'}
  $held=$false;try{$probe=[IO.File]::Open("$script:DataDir/application.lease",'OpenOrCreate','ReadWrite','None');$probe.Dispose()}catch [IO.IOException]{$held=$true}
  if(-not $held){throw 'IDEA application lease is not held'}
 }
 $bytes=New-Object byte[] 24;$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
 try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
 $code=[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
 $sha=[Security.Cryptography.SHA256]::Create();try{$digest=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($code)))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
 $nonce=[guid]::NewGuid().ToString();$expires=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+90000
 $canonical=([string]$runtime.pid)+'|'+$runtime.bootId+'|'+$runtime.generation+'|'+$nonce+'|'+$expires+'|'+$digest
 $mac=New-Object Security.Cryptography.HMACSHA256;$mac.Key=[Text.Encoding]::UTF8.GetBytes($secret.control)
 try{$signature=([BitConverter]::ToString($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-','').ToLowerInvariant()}finally{$mac.Dispose()}
 $target=Join-Path $script:DataDir 'local-entry-request.json';$part=$target+'.'+$nonce+'.partial'
 Write-Utf8 $part (@{pid=$runtime.pid;bootId=$runtime.bootId;generation=$runtime.generation;nonce=$nonce;expires=$expires;digest=$digest;mac=$signature}|ConvertTo-Json)
 $plain=Get-EmsFileArgument $target;$partPlain=Get-EmsFileArgument $part;if([IO.File]::Exists($plain)){[IO.File]::Replace($partPlain,$plain,[NullString]::Value)}else{[IO.File]::Move($partPlain,$plain)}
 [Console]::WriteLine('本机入口代码（90秒、仅一次；不要通过远程HTTP发送）：')
 [Console]::WriteLine($code)
 [Console]::WriteLine('在本机浏览器访问 http://127.0.0.1:'+ $runtime.localHttpPort +'/local-entry 后输入。账号登录和高风险验证仍然必需。')
}finally{if($part -and [IO.File]::Exists((Get-EmsFileArgument $part))){[IO.File]::Delete((Get-EmsFileArgument $part))};$code=$null;$secret=$null;if($lock){$lock.Dispose()}}
