$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\artifact-common.ps1"
$checks=New-Object Collections.Generic.List[string]
function Check($ok,$text){if(-not $ok){throw "FAILED: $text"};$checks.Add($text);Write-Host "PASS: $text"}
function Reject($Block,$text){$denied=$false;try{& $Block}catch{$denied=$true};Check $denied $text}
$run=New-ArtifactRun $root 'safety-tests'
$results=Join-Path $run.path 'safety-results.txt'
$success=$false
function Reopen-Run($r){
    $file=Join-Path $r.root ('.release-work/runs/.locks/'+$r.id+'.lock')
    $r.lock=[IO.File]::Open($file,'OpenOrCreate','ReadWrite','None')
}
try{
    Reject {Assert-ArtifactPath $run.path $root} 'Project root cannot be treated as a disposable child'
    Reject {Assert-ArtifactPath $run.path ($run.path+'-sibling')} 'Sibling prefix cannot bypass path containment'
    Reject {Assert-ArtifactPath $run.path (Join-Path $run.path '../../data')} 'Traversal outside a task is rejected'
    $foreign=[pscustomobject]@{root=$root;path=$run.path;id=$run.id;manifest=$run.manifest;lock=$null}
    Reject {Remove-ArtifactRun $foreign} 'A borrowed context cannot delete its owner workspace'
    Reject {[IO.File]::Open((Join-Path $root ('.release-work/runs/.locks/'+$run.id+'.lock')),'OpenOrCreate','ReadWrite','None')} 'Concurrent ownership is blocked by an exclusive task lock'
    $protected=Join-Path $run.path 'protected-sentinel';[IO.Directory]::CreateDirectory($protected)|Out-Null
    [IO.File]::WriteAllText((Join-Path $protected 'sentinel.txt'),'KEEP')
    $link=Join-Path $run.path 'junction'
    $null=New-Item -ItemType Junction -Path $link -Target $protected
    try{
        Reject {Assert-ArtifactPath $run.path (Join-Path $link 'sentinel.txt')} 'Junction traversal is rejected'
        Reject {Remove-ArtifactRun $run} 'Nested junction prevents recursive cleanup'
        Check ([IO.File]::ReadAllText((Join-Path $protected 'sentinel.txt')) -eq 'KEEP') 'Protected sentinel survives rejected cleanup'
    }finally{[IO.Directory]::Delete($link)}
    $case=New-ArtifactRun $root 'injected-build-failure'
    [IO.File]::WriteAllText((Join-Path $case.path 'logs/build.log'),'intentional build failure')
    $id=$case.id;$code=Complete-ArtifactRun $case $false 'intentional build failure'
    Check ($code -eq 1 -and -not(Test-Path -LiteralPath $case.path)) 'Failed build preserves evidence and reclaims its full workspace'
    $proof=Join-Path $root ('.release-evidence/1.1.3/'+$id+'/build.log')
    Check ((Test-Path -LiteralPath $proof) -and [IO.File]::ReadAllText($proof).Contains('intentional')) 'Failure log remains readable outside disposable work'
    $case=New-ArtifactRun $root 'injected-stop-failure'
    $fixture=Join-Path $case.path 'fixture'
    [IO.Directory]::CreateDirectory((Join-Path $fixture 'launcher'))|Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $fixture 'data'))|Out-Null
    [IO.File]::WriteAllText((Join-Path $fixture 'launcher/stop-ems.ps1'),'exit 1')
    Write-ArtifactJson (Join-Path $fixture 'data/instance.json') @{app=$null;database=$null;nativeDrive=''}
    Register-ArtifactInstance $case $fixture
    $code=Complete-ArtifactRun $case $true
    Check ($code -eq 2 -and (Test-Path -LiteralPath $case.path)) 'Stop failure retains the fixture and returns cleanup-only code 2'
    [IO.File]::WriteAllText((Join-Path $fixture 'launcher/stop-ems.ps1'),'exit 0')
    Reopen-Run $case
    Check ((Complete-ArtifactRun $case $true) -eq 0) 'Cleanup can be retried after the stop failure is resolved'
    $case=New-ArtifactRun $root 'native-output-status'
    $fixture=Join-Path $case.path 'fixture'
    [IO.Directory]::CreateDirectory((Join-Path $fixture 'launcher'))|Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $fixture 'data'))|Out-Null
    [IO.File]::WriteAllText((Join-Path $fixture 'launcher/stop-ems.ps1'),"Write-Output 'native diagnostic';exit 0")
    Write-ArtifactJson (Join-Path $fixture 'data/instance.json') @{app=$null;database=$null;nativeDrive=''}
    Register-ArtifactInstance $case $fixture
    $code=Complete-ArtifactRun $case $false 'primary failure'
    Check ($code -is [int] -and $code -eq 1) 'Native stop output cannot contaminate or change the primary exit code'
    $case=New-ArtifactRun $root 'injected-lock-failure'
    $file=Join-Path $case.path 'busy.bin'
    $held=[IO.File]::Open($file,'Create','ReadWrite','None')
    try{$code=Complete-ArtifactRun $case $false 'original test failure';Check ($code -eq 1 -and (Test-Path -LiteralPath $case.path)) 'File lock does not overwrite the original failure or force removal'}finally{$held.Dispose()}
    Reopen-Run $case
    Check ((Complete-ArtifactRun $case $true) -eq 0) 'Released file permits a safe cleanup retry'
    $case=New-ArtifactRun $root 'interrupted-task'
    $case.lock.Dispose();$case.lock=$null
    $originalOwner=$case.manifest.ownerId
    $case.manifest.ownerId=2147483000
    Write-ArtifactJson (Join-Path $case.path 'run.json') $case.manifest
    $borrow=Read-ArtifactContext $root (Join-Path $case.path 'run.json')
    Check (-not(Get-Process -Id $borrow.manifest.ownerId -ErrorAction SilentlyContinue)) 'Interrupted owner is identifiable without deleting unknown work'
    Reject {Remove-ArtifactRun $borrow} 'An interrupted task is not silently adopted by another caller'
    $case.manifest.ownerId=$originalOwner;Write-ArtifactJson (Join-Path $case.path 'run.json') $case.manifest;Reopen-Run $case
    $null=Complete-ArtifactRun $case $true
    $case=New-ArtifactRun $root 'active-browser'
    $proc=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 45') -WindowStyle Hidden -PassThru
    Register-ArtifactProcess $case $proc 'browser'
    $code=Complete-ArtifactRun $case $true
    Check ($code -eq 2 -and -not $proc.HasExited) 'A still-running browser blocks directory cleanup without killing unrelated processes'
    $proc.Kill();$null=$proc.WaitForExit(10000);Reopen-Run $case;$null=Complete-ArtifactRun $case $true
    $case=New-ArtifactRun $root 'explicit-keep' -KeepArtifacts
    $code=Complete-ArtifactRun $case $true
    Check ($code -eq 0 -and (Test-Path -LiteralPath $case.path)) 'Explicit keep preserves a completed full task'
    # Test teardown is explicit, not automatic retention.
    $case.manifest.keep=$false;Write-ArtifactJson (Join-Path $case.path 'run.json') $case.manifest;Reopen-Run $case;$null=Complete-ArtifactRun $case $true
    $case=New-ArtifactRun $root 'evidence-budget'
    [IO.File]::WriteAllText((Join-Path $case.path 'logs/large.log'),('A'*(12MB)))
    [IO.File]::WriteAllText((Join-Path $case.path 'source-client.cnf'),'password=SECRET_TEST_VALUE')
    $null=Complete-ArtifactRun $case $true
    $dest=Join-Path $root ('.release-evidence/1.1.3/'+$case.id)
    Check ((Get-ArtifactBytes $dest) -le 10MB -and -not(Test-Path -LiteralPath (Join-Path $dest 'source-client.cnf'))) 'Evidence is capped and excludes credential files'
    Check ([IO.File]::ReadAllText((Join-Path $dest 'large.log')).StartsWith('[Earlier log bytes truncated]')) 'Truncated logs carry an explicit truncation notice'
    $childContext=Join-Path $run.path 'interrupted-context.txt'
    $common=Join-Path $root 'launcher/artifact-common.ps1'
    $scriptText=". '"+$common.Replace("'","''")+"';"+'$r=New-ArtifactRun '+"'"+$root.Replace("'","''")+"' 'killed-owner';"+'[IO.File]::WriteAllText('+"'"+$childContext.Replace("'","''")+"'"+',(Join-Path $r.path "run.json"));Start-Sleep -Seconds 60'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($scriptText))
    $child=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru
    for($i=0;$i -lt 100 -and -not(Test-Path -LiteralPath $childContext);$i++){Start-Sleep -Milliseconds 100}
    Check (Test-Path -LiteralPath $childContext) 'Real child task publishes its owned context'
    $path=[IO.File]::ReadAllText($childContext)
    Reject {Recover-ArtifactRun $root $path} 'Recovery refuses a task with a live owner'
    $child.Kill();$null=$child.WaitForExit(10000)
    $recovered=Recover-ArtifactRun $root $path
    Check ((Complete-ArtifactRun $recovered $false 'owner was intentionally terminated') -eq 1) 'Explicit recovery after real owner termination preserves the failure and removes owned work'
    $mock=Join-Path $run.path 'mock-project'
    [IO.Directory]::CreateDirectory((Join-Path $mock 'backend'))|Out-Null
    [IO.File]::WriteAllText((Join-Path $mock 'backend/pom.xml'),'<project><version>1.1.3</version></project>')
    $evidenceBase=Join-Path $mock '.release-evidence/1.1.3'
    for($i=0;$i -lt 21;$i++){
        $id='retention-'+$i.ToString('000')
        $dir=Join-Path $evidenceBase $id
        [IO.Directory]::CreateDirectory($dir)|Out-Null
        Write-ArtifactJson (Join-Path $dir 'evidence.json') @{schema=1;managed=$true;root=$mock;id=$id;path=(Join-Path $mock ('.release-work/runs/'+$id));status='succeeded';keep=$false;createdAt=(Get-Date).AddMinutes(-100+$i).ToString('o')}
        $large=[IO.File]::Create((Join-Path $dir 'synthetic.log'));try{$large.SetLength(11MB)}finally{$large.Dispose()}
    }
    $unknown=Join-Path $evidenceBase 'unmanaged'
    [IO.Directory]::CreateDirectory($unknown)|Out-Null
    [IO.File]::WriteAllText((Join-Path $unknown 'sentinel.txt'),'KEEP')
    Limit-ArtifactEvidence $mock
    $kept=@(Get-ArtifactFiles $evidenceBase|Where-Object{$_.Name -eq 'evidence.json'})
    Check ($kept.Count -le 20 -and (Get-ArtifactBytes (Join-Path $mock '.release-evidence')) -le 200MB) 'Evidence retention enforces both count and total-byte budgets'
    Check ([IO.File]::ReadAllText((Join-Path $unknown 'sentinel.txt')) -eq 'KEEP') 'Unknown evidence is never adopted for automatic deletion'
    $success=$true
}catch{$reason=$_.Exception.Message;throw}finally{
    [IO.File]::WriteAllText($results,($checks -join [Environment]::NewLine),(New-Object Text.UTF8Encoding($false)))
    $code=Complete-ArtifactRun $run $success $reason
}
exit $code
