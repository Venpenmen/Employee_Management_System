param([switch]$AsJson)
$ErrorActionPreference='Stop'
$rows=@(Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*'}|ForEach-Object{
 $ip=$_;$a=Get-NetAdapter -InterfaceIndex $ip.InterfaceIndex -ErrorAction SilentlyContinue;$p=Get-NetConnectionProfile -InterfaceIndex $ip.InterfaceIndex -ErrorAction SilentlyContinue
 [pscustomobject]@{address=$ip.IPAddress;interface=$ip.InterfaceAlias;hardware=([bool]$a.HardwareInterface);status=[string]$a.Status;category=[string]$p.NetworkCategory;prefix=$ip.PrefixLength}
})
if($AsJson){ConvertTo-Json -InputObject $rows -Depth 4}else{
 $rows|Format-Table
 Get-NetFirewallProfile|Select-Object Name,Enabled|Format-Table
 Write-Host 'Read-only inspection. No network/firewall/service changes were made.'
}
