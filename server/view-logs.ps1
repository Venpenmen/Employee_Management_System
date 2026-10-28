param([ValidateSet('server','portable','dev')][string]$Mode='server',[ValidateSet('application','application-error','mysql-error','launcher')][string]$Name='application',[int]$Tail=80)
$ErrorActionPreference='Stop'
if($Tail -lt 1 -or $Tail -gt 1000){throw 'Tail must be 1..1000'}
Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot) "logs\$Mode\$Name.log") -Tail $Tail -Encoding UTF8
