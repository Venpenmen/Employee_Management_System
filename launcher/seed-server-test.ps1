param([Parameter(Mandatory=$true)][string]$Fixture,[string]$Mode='server')
$ErrorActionPreference='Stop'
. "$Fixture\server\runtime-common.ps1" -Mode $Mode
if(-not(Test-Path -LiteralPath "$Fixture\test-fixture.json")){throw 'Not an isolated test fixture'}
$lock=$null;$old=@{}
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s;$cnf=Write-ClientConfig $s
 $secret=Read-EmsSecrets
 $vars=@{EMS_SETUP_NO='260001';EMS_SETUP_NAME='测试高层';EMS_SETUP_ID='110101199001019999';EMS_SETUP_PHONE='13800000001';EMS_SETUP_BIRTH='1990-01-01';EMS_SETUP_HIRE='2020-01-01';EMS_SETUP_PASSWORD=$env:EMS_TEST_PASSWORD;EMS_SETUP_URL="jdbc:mysql://127.0.0.1:$($s.dbPort)/$script:DbName"+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";EMS_DB_PASSWORD=$secret.app}
 foreach($key in $vars.Keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$vars[$key],'Process')}
 Invoke-EmsTool $script:JavaExe @('-Dloader.main=com.example.ems.tools.LocalSetup','-cp',$script:JarFile,'org.springframework.boot.loader.launch.PropertiesLauncher')|Out-Null
 # Second initialization must be refused without creating records.
 $refused=$false;try{Invoke-EmsTool $script:JavaExe @('-Dloader.main=com.example.ems.tools.LocalSetup','-cp',$script:JarFile,'org.springframework.boot.loader.launch.PropertiesLauncher')|Out-Null}catch{$refused=$true}
 if(-not $refused){throw 'Repeated local setup was accepted'}
 $sql=@'
INSERT INTO department(id,department_name,base_salary,status) VALUES(1,'测试研发部',5000,1),(2,'测试行政部',4500,1);
INSERT INTO position_info(id,department_id,position_name,position_salary,is_manager_position) VALUES(1,1,'测试经理',3000,1),(2,1,'测试工程师',1200,0),(3,2,'测试专员',1000,0);
INSERT INTO employee(employee_no,name,identity_number,birth_date,phone,department_id,position_id,user_type,hire_date,employment_status) VALUES
('260002','测试经理','110101199001019998','1990-01-01','13800000002',1,1,1,'2020-01-01',1),
('260003','测试员工甲','110101199001019997','1990-01-01','13800000003',1,2,0,'2020-01-01',1),
('260004','测试员工乙','110101199001019996','1990-01-01','13800000004',2,3,0,'2020-01-01',1),
('260099','会话测试专用','110101199001019990','1990-01-01','13800000099',NULL,NULL,0,'2020-01-01',1);
INSERT INTO user_account(employee_id,password_hash) SELECT e.id,(SELECT password_hash FROM user_account WHERE employee_id=1) FROM employee e WHERE e.employee_no IN('260002','260003','260004','260099');
UPDATE department SET manager_id=(SELECT id FROM employee WHERE employee_no='260002') WHERE id=1;
'@
 Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",$script:DbName) -InputText $sql|Out-Null
 $grants=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N','-e',"SHOW GRANTS FOR 'ems_app'@'127.0.0.1'")
 if($grants -match 'ALL PRIVILEGES|GRANT OPTION' -or $grants -notmatch 'SELECT, INSERT, UPDATE, DELETE'){throw 'Unexpected application privileges'}
 Write-Host 'PASS: empty initialization, repeat-setup refusal and least-privilege account'
}finally{foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')};Clear-ClientConfigs;if($lock){$lock.Dispose()}}
