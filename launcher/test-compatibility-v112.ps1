param([Parameter(Mandatory=$true)][string]$ContextPath,[Parameter(Mandatory=$true)][string]$JavaExe,[string]$AdminPassword='Admin123')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$shell="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
. "$PSScriptRoot\artifact-common.ps1"
$run=Read-ArtifactContext $root $ContextPath
if (-not $env:EMS_DB_URL -or -not $env:EMS_DB_USER -or -not $env:EMS_DB_PASSWORD) { throw 'Supply explicit EMS_DB_URL, EMS_DB_USER and EMS_DB_PASSWORD for an isolated development test database.' }
$info=Get-Content -LiteralPath (Join-Path $run.path 'qa-context.json') -Raw -Encoding UTF8|ConvertFrom-Json
$null=Assert-ArtifactPath $run.path $info.fixture
$checks=New-Object Collections.Generic.List[string]
function Check([bool]$ok,[string]$text){if(-not $ok){throw "FAILED: $text"};$checks.Add($text);Write-Host "PASS: $text"}
# Export fixture numeric cells for an independent OpenXML check.
. (Join-Path $info.fixture 'launcher/portable-common.ps1')
$state=Read-EmsState
Mount-EmsNativeRoot $state
$config=Write-ClientConfig $state
$query="SELECT e.employee_no,s.department_base_salary,s.position_salary,s.reward,s.subsidy,s.overtime_pay,s.other_income,s.deduction,s.other_deduction,s.payable_salary,s.actual_salary FROM salary_record s JOIN employee e ON e.id=s.employee_id WHERE salary_month='2098-12' ORDER BY employee_no"
$numeric=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config",'-N','-B','ems_db','-e',$query)
Write-Utf8 (Join-Path $run.path "numeric-private.tsv") $numeric
& "$PSScriptRoot\verify-excel.ps1" -Workbook (Join-Path $run.path '验收 空格目录/salary-export.xlsx') -ExpectedTsv (Join-Path $run.path 'numeric-private.tsv') -OutputFile (Join-Path $run.path 'numeric-results.txt')
# The assignee count must exclude requests assigned to others and completed requests.
$managerNo=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config",'-N','-B','ems_db','-e',"SELECT employee_no FROM employee WHERE user_type=1 AND employment_status=1 ORDER BY id LIMIT 1")
$manager=New-Object Microsoft.PowerShell.Commands.WebRequestSession
$null=Invoke-WebRequest -UseBasicParsing -Uri "$($info.baseUrl)/login" -Method Post -WebSession $manager -Body @{employeeNo=$managerNo;password=$AdminPassword}
$approver=(Invoke-RestMethod -Uri "$($info.baseUrl)/api/me" -WebSession $manager).data.id
$count=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config",'-N','-B','ems_db','-e',"SELECT COUNT(*) FROM leave_request WHERE status=1 AND current_approver_id=$approver")
$page=Invoke-WebRequest -UseBasicParsing -Uri "$($info.baseUrl)/dashboard" -WebSession $manager
Check ($page.Content -match "待我审批 $count 条") 'Dashboard pending count equals the current approver and pending-state SQL'
# Probe an anonymous form error: never render a signed-in nav or a stack trace.
$invalid=Invoke-WebRequest -UseBasicParsing -Uri "$($info.baseUrl)/register" -Method Post -Body @{employeeNo='260000'}
Check ($invalid.Content -notmatch 'SpelEvaluationException|org\.springframework|jdbc:mysql|<pre>|action="/logout"') 'Anonymous validation error does not expose implementation details or signed-in navigation'
Check ($invalid.StatusCode -eq 200 -and $invalid.Content -match '两次密码输入不一致') 'Malformed registration redisplays the form with validation feedback'
& $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $info.fixture 'launcher/stop-ems.ps1')
Check ($LASTEXITCODE -eq 0) 'Browser fixture stops normally'
$probe=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,8080)
try{$probe.Start()}catch{throw 'Development port 8080 is occupied; no unrelated process will be stopped'}finally{$probe.Stop()}
# Start the main class as IDEA does, with external credentials and no Spring profile.
$cp=(Join-Path $root 'backend/target/classes')+';'+[IO.File]::ReadAllText((Join-Path $run.path 'java-classpath.txt')).Trim()
$dev=Start-Process -FilePath $JavaExe -ArgumentList @('-cp',('"'+$cp+'"'),'com.example.ems.EmsApplication') -WorkingDirectory "$root\backend" -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run.path 'logs/developer-main.log') -RedirectStandardError (Join-Path $run.path 'logs/developer-error.log')
Register-ArtifactProcess $run $dev 'developer-main'
$devStarted=$dev.StartTime
$startedPortable=$false
try {
 for($i=0;$i -lt 60;$i++){
  try{$login=Invoke-WebRequest -UseBasicParsing -Uri http://127.0.0.1:8080/login -TimeoutSec 2;if($login.StatusCode -eq 200){break}}catch{}
  Start-Sleep -Milliseconds 500
 }
 Check (-not $dev.HasExited -and $login.StatusCode -eq 200) 'Default EmsApplication.main starts with local MySQL and port 8080'
 $session=New-Object Microsoft.PowerShell.Commands.WebRequestSession
 $null=Invoke-WebRequest -UseBasicParsing -Uri http://127.0.0.1:8080/login -Method Post -WebSession $session -Body @{employeeNo=$info.adminNo;password=$AdminPassword}
 foreach($route in @('/profile','/employees','/attendance/manage','/admin/salaries','/leaves/approvals')){
  $page=Invoke-WebRequest -UseBasicParsing -Uri ('http://127.0.0.1:8080'+$route) -WebSession $session
  Check ($page.StatusCode -eq 200) "Development/default mode page works: $route"
 }
 . (Join-Path $info.fixture 'launcher/portable-common.ps1')
 $existing=Read-EmsState
 if($existing -and ((Get-OwnedProcess $existing.app) -or (Get-OwnedProcess $existing.database))){throw "Original portable instance was already running"}
 $marker=(Get-Item -LiteralPath (Join-Path $info.fixture 'data/snapshot.ready')).LastWriteTimeUtc
 $startedPortable=$true
 & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $info.fixture 'launcher/start-ems.ps1') -NoBrowser
 Check ($LASTEXITCODE -eq 0) 'Original used portable database upgrades and starts without replacing data'
 $portable=Read-EmsState
 Check ($portable.httpPort -ne 8080 -and $portable.app.id -ne $dev.Id) 'Developer and portable instances use separate ports and processes'
 Mount-EmsNativeRoot $portable
 $config=Write-ClientConfig $portable
 $tableCount=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$config",'-N','-B','ems_db','-e',"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ems_db' AND table_type='BASE TABLE'")
 Check ($tableCount -eq '17') 'Upgraded used database retains exactly seventeen business tables'
 Check ((Get-Item -LiteralPath (Join-Path $info.fixture 'data/snapshot.ready')).LastWriteTimeUtc -eq $marker) 'Used database snapshot marker is unchanged: no snapshot reimport'
 & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $info.fixture 'launcher/stop-ems.ps1')
 Check ($LASTEXITCODE -eq 0) 'Original portable instance stops normally'
 $startedPortable=$false
 $dev.Refresh()
 Check (-not $dev.HasExited -and (Invoke-WebRequest -UseBasicParsing -Uri http://127.0.0.1:8080/profile -WebSession $session).StatusCode -eq 200) 'Stopping BAT leaves the default development application and its database available'
} finally {
 try{if($startedPortable){& $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $info.fixture 'launcher/stop-ems.ps1')}}finally{
 $dev.Refresh()
 if(-not $dev.HasExited -and $dev.StartTime -eq $devStarted){$dev.Kill();$null=$dev.WaitForExit(10000)}
 }
}
Write-Utf8 (Join-Path $run.path 'compatibility-results.txt') ($checks -join [Environment]::NewLine)
