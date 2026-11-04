# RC2 pure policy; configuration is never inferred from a forwarded header.
function Resolve-Rc2Network($Config,[string]$RuntimeMode){
 if(-not $Config){$Config=[pscustomobject]@{format=1;mode='safe';generation='default-safe';httpsEnabled=$false}}
 $allowed=@('format','mode','generation','httpsEnabled','httpsPort','secureOrigin','keyStore','trustCertificate','testOrigin','compatAcknowledged','edgeTls')
 foreach($name in $Config.PSObject.Properties.Name){if($name -notin $allowed){throw 'Unknown RC2 network configuration key'}}
 if($Config.format -ne 1 -or $Config.mode -notin @('safe','compat') -or $Config.generation -notmatch '^[A-Za-z0-9-]{1,80}$'){throw 'Invalid RC2 network configuration'}
 foreach($name in @('httpsEnabled','compatAcknowledged','edgeTls')){if($null -ne $Config.$name -and $Config.$name -isnot [bool]){throw 'Network acknowledgement, edge TLS and HTTPS flags must be booleans'}}
 $httpsPort=8484;if($null -ne $Config.httpsPort){if($Config.httpsPort -isnot [int] -and $Config.httpsPort -isnot [long]){throw 'HTTPS port must be an integer'};$httpsPort=[int]$Config.httpsPort}
 if($httpsPort -lt 1024 -or $httpsPort -gt 65535){throw 'Invalid HTTPS port'}
 if($RuntimeMode -ne 'server' -and ($Config.mode -ne 'safe' -or $Config.httpsEnabled)){throw 'Dev/portable only support safe local entry'}
 if($Config.edgeTls -eq $true -and $Config.mode -ne 'compat'){throw 'Edge TLS tolerance is only available in compatibility mode'}
 if($Config.mode -eq 'compat'){
  if($Config.compatAcknowledged -ne $true){throw 'Confirm synthetic or deidentified test data before compatibility mode'}
  if(-not [string]::IsNullOrWhiteSpace([string]$Config.testOrigin)){$u=$null;if(-not [Uri]::TryCreate([string]$Config.testOrigin,'Absolute',[ref]$u) -or $u.Scheme -notin @('http','https') -or $u.UserInfo -or $u.Query -or $u.Fragment -or $u.AbsolutePath -ne '/'){throw 'Network test origin hint must be one plain http or https origin'}}
 }
 # Remote TLS material/origin failures are reported by the isolated connector;
 # they never open a remote HTTP listener or prevent gated local management.
 [pscustomobject]@{format=1;mode=[string]$Config.mode;generation=[string]$Config.generation;httpsEnabled=($Config.httpsEnabled -eq $true);httpsPort=$httpsPort;secureOrigin=[string]$Config.secureOrigin;keyStore=[string]$Config.keyStore;trustCertificate=[string]$Config.trustCertificate;testOrigin=[string]$Config.testOrigin;compatAcknowledged=($Config.compatAcknowledged -eq $true);edgeTls=($Config.edgeTls -eq $true)}
}
function Save-Rc2Network($Value){
 $null=Resolve-Rc2Network $Value 'server'
 $lock=$null;$lease=$null;$part=$null
 try{
  $lock=Enter-EmsLock;$state=Read-EmsState
  if($state -and (Get-OwnedProcess $state.app)){throw 'Stop only this candidate before changing its network mode'}
  $lease=[IO.File]::Open("$script:DataDir/application.lease",'OpenOrCreate','ReadWrite','None')
  $Value.generation=[guid]::NewGuid().ToString()
  $target=Join-Path $script:EmsRoot 'server-complete-rc2/network-mode.json';Assert-ArtifactPath $script:EmsRoot $target|Out-Null;$part=$target+'.'+[guid]::NewGuid().ToString()+'.partial'
  Write-Utf8 $part ($Value|ConvertTo-Json -Depth 4)
  if(Test-Path -LiteralPath $target){[IO.File]::Replace($part,$target,[NullString]::Value)}else{[IO.File]::Move($part,$target)}
 }finally{if($part -and (Test-Path -LiteralPath $part)){Remove-Item -LiteralPath $part};if($lease){$lease.Dispose()};if($lock){$lock.Dispose()}}
}
