param([string]$AdminPassword=$(if($env:EMS_TEST_ADMIN_PASSWORD){$env:EMS_TEST_ADMIN_PASSWORD}else{'Admin123'}),[switch]$OpenBrowser,[string]$ZipPath,[string]$ContextPath,[switch]$KeepArtifacts,[string]$NodeExe,[string]$BrowserExe,[string]$JavaExe,[string]$MavenExe)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\test-profile.ps1"
. "$PSScriptRoot\test-v112.ps1"
. "$PSScriptRoot\artifact-common.ps1"
$ownsRun=-not $ContextPath
if($ownsRun){$run=New-ArtifactRun $sourceRoot 'acceptance' -KeepArtifacts:$KeepArtifacts}else{$run=Read-ArtifactContext $sourceRoot $ContextPath}
$workRoot=$run.path
$caseRoot=Join-Path $run.path '验收 空格目录'
$version=Get-ArtifactVersion $sourceRoot
$zip=if($ZipPath){[IO.Path]::GetFullPath($ZipPath)}else{Join-Path $sourceRoot "dist\EMS员工管理系统_v$($version)_Windows_x64.zip"}
$shell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$oldPath = $env:Path
$oldJavaHome = $env:JAVA_HOME
$listeners = @()
$report = New-Object Collections.Generic.List[string]
$fixture = $null
function Assert-Test([bool]$Condition, [string]$Message) { if (-not $Condition) { throw "FAILED: $Message" }; $report.Add($Message); Write-Host "PASS: $Message" }
function Start-TestInstance {
    & $shell -NoProfile -ExecutionPolicy Bypass -File "$fixture\launcher\start-ems.ps1" -NoBrowser
    Assert-Test ($LASTEXITCODE -eq 0) 'Portable start completed without installed Java/Maven/MySQL in PATH'
    $script:state = Get-Content -LiteralPath "$fixture\data\instance.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    $script:baseUrl = "http://127.0.0.1:$($script:state.httpPort)"
}
function Test-Login([string]$Number, [string]$Password) {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/login" -Method Post -Body @{employeeNo=$Number; password=$Password} -WebSession $session
    $me = Invoke-RestMethod -Uri "$baseUrl/api/me" -WebSession $session
    if ($me.data.employeeNo -ne $Number) { throw 'Fixture login failed. Supply the actual source administrator password using -AdminPassword.' }
    return $session
}
function Invoke-FixtureSql([string]$Sql) {
    $config = Write-ClientConfig $script:state
    return Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config", '-N', '-B', 'ems_db', '-e', $Sql)
}
try {
    $NodeExe=Resolve-ArtifactTool $NodeExe 'node.exe'
    $JavaExe=Resolve-ArtifactTool $JavaExe 'java.exe'
    $MavenExe=Resolve-ArtifactTool $MavenExe 'mvn.cmd'
    if(-not $BrowserExe){$BrowserExe=@("$env:ProgramFiles\Google\Chrome\Application\chrome.exe",(Join-Path ([Environment]::GetEnvironmentVariable('ProgramFiles(x86)')) 'Microsoft/Edge/Application/msedge.exe'))|Where-Object{Test-Path -LiteralPath $_}|Select-Object -First 1}
    if(-not $BrowserExe){throw 'Supply -BrowserExe for Chrome or Edge'}
    $BrowserExe=Resolve-ArtifactTool $BrowserExe 'chrome.exe'
    $classpath=Join-Path $run.path 'java-classpath.txt'
    $nativeCode=Invoke-ArtifactNative $MavenExe @('-q','-f',"$sourceRoot\backend\pom.xml",'dependency:build-classpath',"-Dmdep.outputFile=$classpath",'-Dmdep.regenerateFile=true') (Join-Path $run.path 'logs/classpath.log')
    if($nativeCode -ne 0 -or -not(Test-Path -LiteralPath $classpath)){throw 'Development classpath was not generated for this task'}
    [IO.Directory]::CreateDirectory($caseRoot) | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $caseRoot
    $fixture = Join-Path $caseRoot ("EMS_v"+$version)
    Register-ArtifactInstance $run $fixture
    Assert-Test (-not (Test-Path -LiteralPath "$fixture\data")) 'ZIP contains no used database or process credentials'
    . "$fixture\launcher\portable-common.ps1"
    $env:Path = "$env:SystemRoot\System32;$env:SystemRoot"
    Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue
    foreach ($port in @(8080,13306)) {
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $port)
        try { $listener.Start(); $listeners += $listener } catch { $listener.Stop() }
    }
    # Exercise recovery from an interrupted first initialization, with no user database present.
    [IO.Directory]::CreateDirectory("$fixture\data\mysql-initializing") | Out-Null
    Write-Utf8 "$fixture\data\mysql-initializing\interrupted-test.txt" 'disposable test fixture'
    Start-TestInstance
    Assert-Test ($state.httpPort -ne 8080 -and $state.dbPort -ne 13306) 'Occupied HTTP/database ports were preserved and alternatives selected'
    Assert-Test (@(Get-ChildItem -LiteralPath "$fixture\data" -Directory -Filter 'mysql-incomplete-*').Count -eq 1) 'Interrupted initialization was preserved and safely retried'
    Mount-EmsNativeRoot $state
    Get-SnapshotCounts $state
    Assert-Test $true 'All restored table counts match the source snapshot'
    $initialApp = $state.app.id
    & $shell -NoProfile -ExecutionPolicy Bypass -File "$fixture\launcher\start-ems.ps1" -NoBrowser
    $repeated = Get-Content -LiteralPath "$fixture\data\instance.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Test ($LASTEXITCODE -eq 0 -and $repeated.app.id -eq $initialApp) 'Repeated start reuses the same backend process'
    $heldLock = [IO.File]::Open("$fixture\data\launcher.lock", 'OpenOrCreate', 'ReadWrite', 'None')
    try {
        & $shell -NoProfile -ExecutionPolicy Bypass -File "$fixture\launcher\start-ems.ps1" -NoBrowser
        Assert-Test ($LASTEXITCODE -ne 0 -and (Test-EmsReady $state)) 'Concurrent lifecycle operations are rejected without stopping the running instance'
    } finally { $heldLock.Dispose() }
    $forbidden = $false
    try { Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/internal/portable/status" -ErrorAction Stop | Out-Null }
    catch { $forbidden = [int]$_.Exception.Response.StatusCode -eq 403 }
    Assert-Test $forbidden 'Lifecycle API rejects requests without the instance token'

    $adminNo = Invoke-FixtureSql 'SELECT employee_no FROM employee WHERE user_type=2 AND employment_status=1 ORDER BY id LIMIT 1'
    $admin = Test-Login $adminNo $AdminPassword
    foreach ($route in @('/dashboard','/employees','/admin/departments','/admin/positions/manage','/admin/salaries','/attendance/manage','/leaves/approvals','/admin/logs')) {
        $page = Invoke-WebRequest -UseBasicParsing -Uri ($baseUrl+$route) -WebSession $admin
        Assert-Test ($page.StatusCode -eq 200 -and $page.Content -notmatch '<title>操作失败</title>') "Authenticated page works: $route"
    }
    $department = [long](Invoke-FixtureSql 'SELECT id FROM department WHERE status=1 AND manager_id IS NOT NULL ORDER BY id LIMIT 1')
    $position = [long](Invoke-FixtureSql "SELECT id FROM position_info WHERE department_id=$department AND status=1 AND is_manager_position=0 ORDER BY id LIMIT 1")
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees/recruit" -Method Post -WebSession $admin -Body @{name='便携验收员工';identityNumber='110101199901019999';birthDate='1999-01-01';hireDate='2026-01-01';departmentId=$department;positionId=$position}
    $employeeNo = Invoke-FixtureSql "SELECT employee_no FROM employee WHERE identity_number='110101199901019999'"
    Assert-Test ($employeeNo -match '^\d{6}$') 'Recruitment writes to the independent fixture database'
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/register" -Method Post -Body @{employeeNo=$employeeNo;name='便携验收员工';identitySuffix='019999';phone='13900009999';password='Fixture123';confirmPassword='Fixture123'}
    $employee = Test-Login $employeeNo 'Fixture123'
    Assert-Test $true 'New employee registration and login work'
    Invoke-ProfileAcceptance
    Invoke-V112Acceptance
    foreach ($route in @('/attendance/check-in','/attendance/check-out')) { $null = Invoke-WebRequest -UseBasicParsing -Uri ($baseUrl+$route) -Method Post -WebSession $employee }
    Assert-Test ((Invoke-FixtureSql "SELECT COUNT(*) FROM attendance_record a JOIN employee e ON e.id=a.employee_id WHERE e.employee_no='$employeeNo' AND check_in_time IS NOT NULL AND check_out_time IS NOT NULL") -eq '1') 'Check-in and check-out are saved'
    $start = (Get-Date).Date.AddDays(10).AddHours(9).ToString("yyyy-MM-dd'T'HH:mm")
    $end = (Get-Date).Date.AddDays(10).AddHours(18).ToString("yyyy-MM-dd'T'HH:mm")
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/leaves" -Method Post -WebSession $employee -Body @{leaveType='PERSONAL';startTime=$start;endTime=$end;reason='Portable acceptance test'}
    $requestNo = Invoke-FixtureSql "SELECT l.request_no FROM leave_request l JOIN employee e ON e.id=l.employee_id WHERE e.employee_no='$employeeNo' AND l.status=1 ORDER BY l.id DESC LIMIT 1"
    Assert-Test ($requestNo -match '^L') 'Leave application is routed to the department manager'
    # Only in this disposable fixture: use the administrator hash to authenticate the current manager.
    $null = Invoke-FixtureSql "UPDATE user_account a JOIN department d ON d.manager_id=a.employee_id SET a.password_hash=(SELECT h.password_hash FROM (SELECT u.password_hash FROM user_account u JOIN employee e ON e.id=u.employee_id WHERE e.employee_no='$adminNo') h) WHERE d.id=$department"
    $managerNo = Invoke-FixtureSql "SELECT e.employee_no FROM employee e JOIN department d ON d.manager_id=e.id WHERE d.id=$department"
    $manager = Test-Login $managerNo $AdminPassword
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/leaves/$requestNo/approve" -Method Post -WebSession $manager -Body @{opinion='Portable acceptance test'}
    Assert-Test ((Invoke-FixtureSql "SELECT status FROM leave_request WHERE request_no='$requestNo'") -eq '2') 'Manager approval and leave-attendance SQL work'
    $month = '2098-12'
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/salaries/settle" -Method Post -WebSession $admin -Body @{month=$month}
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/salaries/publish" -Method Post -WebSession $admin -Body @{month=$month}
    Assert-Test ([int](Invoke-FixtureSql "SELECT COUNT(*) FROM salary_record WHERE salary_month='$month' AND status=3") -gt 0) 'Salary settlement and publication work'
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/salaries/export?month=$month" -WebSession $admin -OutFile "$caseRoot\salary-export.xlsx"
    Assert-Test ((Get-Item -LiteralPath "$caseRoot\salary-export.xlsx").Length -gt 1000) 'Salary Excel export works with the bundled Java runtime'
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/my-salaries?month=$month" -WebSession $employee
    $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/password" -Method Post -WebSession $employee -Body @{oldPassword='Fixture123';newPassword='Changed456';confirmPassword='Changed456'}
    $null = Test-Login $employeeNo 'Changed456'
    Assert-Test $true 'Password change is effective'

    $markerTime = (Get-Item -LiteralPath "$fixture\data\snapshot.ready").LastWriteTimeUtc
    $deadApp = Get-OwnedProcess $state.app
    $deadApp.Kill()
    $deadApp.WaitForExit(10000) | Out-Null
    Start-TestInstance
    Assert-Test ($state.database.id -eq $repeated.database.id) 'Unexpected backend exit recovers without restarting the database'
    & $shell -NoProfile -ExecutionPolicy Bypass -File "$fixture\launcher\stop-ems.ps1"
    Assert-Test ($LASTEXITCODE -eq 0) 'Stop script shuts down the portable processes'
    $stopped = Get-Content -LiteralPath "$fixture\data\instance.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Test (-not $stopped.app -and -not $stopped.database -and -not $stopped.nativeDrive) 'Stop removes process records and temporary drive mapping'
    Move-Item -LiteralPath "$fixture\data\snapshot.ready" -Destination "$fixture\data\snapshot.ready.saved"
    try {
        & $shell -NoProfile -ExecutionPolicy Bypass -File "$fixture\launcher\start-ems.ps1" -NoBrowser
        Assert-Test ($LASTEXITCODE -ne 0) 'A missing marker on a used database refuses destructive snapshot reimport'
    } finally {
        Move-Item -LiteralPath "$fixture\data\snapshot.ready.saved" -Destination "$fixture\data\snapshot.ready"
    }
    $moved = Join-Path $caseRoot '移动后的 项目'
    foreach ($path in @($fixture,$moved)) {
        if (-not [IO.Path]::GetFullPath($path).StartsWith($workRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture move path.' }
    }
    Move-Item -LiteralPath $fixture -Destination $moved
    $fixture = $moved
    Register-ArtifactInstance $run $fixture
    . "$fixture\launcher\portable-common.ps1"
    if ($OpenBrowser) {
        & "$fixture\启动员工管理系统.bat"
        Assert-Test ($LASTEXITCODE -eq 0) 'Start BAT works and requests the default browser'
        $script:state = Get-Content -LiteralPath "$fixture\data\instance.json" -Raw -Encoding UTF8 | ConvertFrom-Json
        $script:baseUrl = "http://127.0.0.1:$($state.httpPort)"
    } else { Start-TestInstance }
    Mount-EmsNativeRoot $state
    $null = Test-Login $employeeNo 'Changed456'
    Test-ProfilePersistence
    Assert-Test ((Get-Item -LiteralPath "$fixture\data\snapshot.ready").LastWriteTimeUtc -eq $markerTime) 'Restart after moving the directory preserves data and does not reimport the snapshot'
    Assert-Test ((Invoke-FixtureSql "SELECT status FROM leave_request WHERE request_no='$requestNo'") -eq '2') 'Business writes survive shutdown and directory relocation'
    Write-ArtifactJson (Join-Path $run.path 'qa-context.json') @{fixture=$fixture;baseUrl=$baseUrl;employeeNo=$employeeNo;adminNo=$adminNo}
    foreach($listener in $listeners){$listener.Stop()}
    $nativeCode=Invoke-ArtifactNative $NodeExe @("$sourceRoot\launcher\test-browser-v112.cjs",$baseUrl,$employeeNo,'Changed456',(Join-Path $run.path 'browser-results.txt'),$BrowserExe,(Join-Path $run.path 'browser-profile')) (Join-Path $run.path 'logs/browser.log')
    if($nativeCode -ne 0){throw "Browser verification failed: $nativeCode"}
    $nativeCode=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$sourceRoot\launcher\test-compatibility-v112.ps1",'-ContextPath',(Join-Path $run.path 'run.json'),'-JavaExe',$JavaExe,'-AdminPassword',$AdminPassword) (Join-Path $run.path 'logs/compatibility.log')
    if($nativeCode -ne 0){throw "Compatibility verification failed: $nativeCode"}
    & "$fixture\停止员工管理系统.bat" --no-pause
    Assert-Test ($LASTEXITCODE -eq 0) 'Stop BAT works after directory relocation'
    Write-Utf8 "$($run.path)\acceptance-results.txt" ($report -join "`r`n")
    Write-Host "All acceptance checks passed. Report: $($run.path)\acceptance-results.txt"
} catch {$primaryFailure=$_} finally {
    if ($fixture -and (Test-Path -LiteralPath "$fixture\data\instance.json")) {try{& $shell -NoProfile -ExecutionPolicy Bypass -File "$fixture\launcher\stop-ems.ps1";if($LASTEXITCODE -ne 0){$stopFailure="Fixture stop failed"}}catch{$stopFailure=$_.Exception.Message}}
    foreach ($listener in $listeners) { $listener.Stop() }
    $env:Path = $oldPath
    $env:JAVA_HOME = $oldJavaHome
}

if($ownsRun){
    $reason=if($primaryFailure){$primaryFailure.Exception.Message}elseif($stopFailure){$stopFailure}else{''}
    exit (Complete-ArtifactRun $run (-not $primaryFailure -and -not $stopFailure) $reason)
}
if($primaryFailure){throw $primaryFailure}
if($stopFailure){throw $stopFailure}
