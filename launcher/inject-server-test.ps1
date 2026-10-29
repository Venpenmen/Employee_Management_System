param([Parameter(Mandatory=$true)][string]$Fixture,[ValidateSet('application','database')][string]$Kind)
$ErrorActionPreference='Stop'
. "$Fixture\server\runtime-common.ps1" -Mode server
if(-not(Test-Path -LiteralPath "$Fixture\test-fixture.json")){throw 'Not a test fixture'}
$lock=$null
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s
 $identity=if($Kind -eq 'application'){$s.app}else{$s.database}
 $p=Get-OwnedProcess $identity;if(-not $p){throw 'Missing owned fault-injection process'}
 # Destructive testing is confined to the synthetic, registered fixture, never a real business instance.
 $p.Kill();if(-not $p.WaitForExit(10000)){throw 'Fault injection process did not exit'}
 Write-Host "Injected $Kind crash only into the verified synthetic test instance."
}finally{Clear-ClientConfigs;if($lock){$lock.Dispose()}}
