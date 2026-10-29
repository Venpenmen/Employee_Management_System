param([Parameter(Mandatory=$true)][string]$Fixture)
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath "$Fixture/test-fixture.json")){throw 'Synthetic fixture required'}
. "$Fixture/server/runtime-common.ps1" -Mode server
$lock=$null
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s;$cnf=Write-ClientConfig $s
 $checks=@{
 "SELECT COUNT(*) FROM department WHERE department_name='回归空部门改' AND status=2"=1
 "SELECT COUNT(*) FROM employee WHERE name='BatchSyntheticA' AND employment_status=0"=1
 "SELECT COUNT(*) FROM user_account a JOIN employee e ON e.id=a.employee_id WHERE e.name='BatchSyntheticA'"=0
 "SELECT user_type FROM employee WHERE employee_no='260003'"=1
 "SELECT user_type FROM employee WHERE employee_no='260002'"=0
 "SELECT COUNT(*) FROM leave_request l JOIN employee e ON e.id=l.employee_id WHERE e.employee_no='260003' AND l.start_time='2033-02-10 09:00:00' AND l.status=2"=1
 }
 foreach($query in $checks.Keys){$actual=[long](Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N',$script:DbName,'-e',$query));if($actual -ne $checks[$query]){throw 'Expanded business database assertion failed'}}
 Write-Host "PASS: $($checks.Count) expanded business database assertions"
}finally{Clear-ClientConfigs;if($lock){$lock.Dispose()}}
