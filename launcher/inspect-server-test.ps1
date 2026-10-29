param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$OutputDir)
$ErrorActionPreference='Stop'
. "$Fixture\server\runtime-common.ps1" -Mode server
if(-not(Test-Path -LiteralPath "$Fixture\test-fixture.json")){throw 'Not an isolated test fixture'}
$lock=$null
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s;$cnf=Write-ClientConfig $s
 $checks=@{
  "SELECT COUNT(*) FROM leave_request"=1;
  "SELECT COUNT(*) FROM leave_approval_record WHERE action='APPROVE'"=1;
  "SELECT COUNT(*) FROM operation_log WHERE operation_type='APPROVE_LEAVE'"=1;
  "SELECT COUNT(*) FROM attendance_record WHERE check_in_time IS NOT NULL"=1;
  "SELECT COUNT(*) FROM employee_education"=1;
  "SELECT COUNT(*) FROM employee_experience"=1;
  "SELECT COUNT(*) FROM employee_skill"=1;
  "SELECT COUNT(*) FROM employee_certificate"=1;
  "SELECT user_type FROM employee WHERE employee_no='260003'"=0
 }
 foreach($sql in $checks.Keys){$value=[long](Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N',$script:DbName,'-e',$sql));if($value -ne $checks[$sql]){throw "Database assertion failed: $sql"}}
 $query="SELECT e.employee_no,s.department_base_salary,s.position_salary,s.reward,s.subsidy,s.overtime_pay,s.other_income,s.deduction,s.other_deduction,s.payable_salary,s.actual_salary FROM salary_record s JOIN employee e ON e.id=s.employee_id WHERE salary_month='2030-06' ORDER BY e.employee_no"
 $tsv=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N','-B',$script:DbName,'-e',$query)
 Assert-ArtifactPath (Split-Path $Fixture) $OutputDir -AllowRoot|Out-Null
 [IO.File]::WriteAllText((Join-Path $OutputDir 'salary-private.tsv'),$tsv,(New-Object Text.UTF8Encoding($false)))
 Write-Host "PASS: $($checks.Count) database consistency assertions"
}finally{Clear-ClientConfigs;if($lock){$lock.Dispose()}}
