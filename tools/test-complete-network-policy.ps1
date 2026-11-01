$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/server-complete/network-policy.ps1"
$script:checks=0
function Check([bool]$Value,[string]$Message){if(-not $Value){throw $Message};$script:checks++;Write-Host ('PASS '+$Message)}
function Refuse([scriptblock]$Code,[string]$Message){$refused=$false;try{& $Code|Out-Null}catch{$refused=$true};Check $refused $Message}
$p=Resolve-CompleteNetwork $null 'server' '';Check ($p.mode -eq 'local' -and $p.address -eq '127.0.0.1' -and -not $p.tls) 'Default is loopback, no proxy-header trust'
Refuse {Resolve-CompleteNetwork @{mode='local'} 'server' '192.0.2.10'} 'Local never expands listening scope'
Refuse {Resolve-CompleteNetwork @{mode='compatibility'} 'server' ''} 'No insecure deployment compatibility mode'
Refuse {Resolve-CompleteNetwork @{mode='lan'} 'portable' '192.0.2.10'} 'Portable cannot become LAN server'
Refuse {Resolve-CompleteNetwork @{mode='lan'} 'server' '0.0.0.0'} 'Wildcard LAN refused'
Refuse {Resolve-CompleteNetwork @{mode='lan'} 'server' ''} 'LAN requires explicit interface choice'
$p=Resolve-CompleteNetwork @{mode='lan';address='192.0.2.10'} 'server' '';Check ($p.address -eq '192.0.2.10' -and -not $p.tls) 'LAN selection retained; no network request made'
$p=Resolve-CompleteNetwork @{mode='proxy';publicOrigin='https://localhost:8484'} 'server' '';Check ($p.address -eq '127.0.0.1' -and $p.tls) 'Proxy requires native TLS and loopback backend'
Refuse {Resolve-CompleteNetwork @{mode='proxy';publicOrigin='https://localhost:8484'} 'server' '192.0.2.10'} 'Proxy backend cannot expose another interface'
foreach($file in Get-ChildItem -LiteralPath "$root/server-complete" -Filter '*.ps1' -File){$tokens=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors);Check ($errors.Count -eq 0) ('Script parses: '+$file.Name)}
Refuse {Resolve-CompleteNetwork @{mode='proxy'} 'server' ''} 'Proxy cannot infer or trust an unknown public origin'
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed task required for result persistence'}
$result=@{passed=$true;checks=$script:checks;scope='pure configuration and script syntax; no live LAN/FRP changes'}
[IO.File]::WriteAllText("$root/.codex-continuation/network-policy-results.json",($result|ConvertTo-Json))
