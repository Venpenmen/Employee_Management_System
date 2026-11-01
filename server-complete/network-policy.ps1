# Pure policy resolution. No firewall, FRP, certificate or system changes.
function Resolve-CompleteNetwork($Config,[string]$RuntimeMode,[string]$RequestedAddress){
 $kind=if($Config){[string]$Config.mode}else{'local'}
 if($kind -notin @('local','lan','proxy')){throw 'Only local, lan or proxy are supported; insecure compatibility is not a deployment mode'}
 if($RuntimeMode -ne 'server' -and $kind -ne 'local'){throw 'Portable/dev require local network mode'}
 $address=$RequestedAddress
 if(-not $address -and $Config){$address=[string]$Config.address}
 if($kind -in @('local','proxy')){if($address -and $address -ne '127.0.0.1'){throw 'Local/proxy bind only to loopback'};$address='127.0.0.1'}
 if($kind -eq 'lan' -and (-not $address -or $address -eq '127.0.0.1' -or $address -eq '0.0.0.0')){throw 'LAN requires one explicitly selected local IPv4 interface'}
 if($kind -eq 'proxy'){
  $origin=$null
  if(-not [Uri]::TryCreate([string]$Config.publicOrigin,[UriKind]::Absolute,[ref]$origin) -or $origin.Scheme -ne 'https' -or $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/'){throw 'Proxy requires one explicit HTTPS public origin without a path or credentials'}
 }
 $tls=$kind -eq 'proxy' -or ($Config -and $Config.tls -eq $true)
 return [pscustomobject]@{mode=$kind;address=$address;tls=[bool]$tls}
}
