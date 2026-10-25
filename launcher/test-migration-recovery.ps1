param(
    [Parameter(Mandatory=$true)][string]$MySqlExe,
    [Parameter(Mandatory=$true)][string]$ClientConfig,
    [Parameter(Mandatory=$true)][string]$OutputFile
)
$ErrorActionPreference='Stop'
# This creates a uniquely named disposable schema; it never selects ems_db.
$schema='ems_acceptance_migration_'+[guid]::NewGuid().ToString('N')
$root=Split-Path -Parent $PSScriptRoot
$checks=New-Object Collections.Generic.List[string]
function Sql([string]$Text,[switch]$ExpectFailure) {
    $psi=New-Object Diagnostics.ProcessStartInfo
    $psi.FileName=$MySqlExe
    $psi.Arguments='--defaults-file="'+$ClientConfig+'" --connect-timeout=5 --batch --skip-column-names --default-character-set=utf8mb4'
    $psi.UseShellExecute=$false;$psi.CreateNoWindow=$true
    $psi.RedirectStandardInput=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
    $p=[Diagnostics.Process]::Start($psi)
    $p.StandardInput.Write($Text);$p.StandardInput.Close()
    $out=$p.StandardOutput.ReadToEnd();$err=$p.StandardError.ReadToEnd();$p.WaitForExit()
    if($ExpectFailure){if($p.ExitCode -eq 0){throw 'Injected migration failure unexpectedly succeeded'}}
    elseif($p.ExitCode -ne 0){throw ('Migration test SQL failed: '+$err)}
    return $out.Trim()
}
function Check([bool]$Ok,[string]$Message){if(!$Ok){throw $Message};$checks.Add($Message);Write-Host "PASS: $Message"}
$base=[IO.File]::ReadAllText((Join-Path $root 'backend/src/main/resources/schema.sql'))
$migration=[IO.File]::ReadAllText((Join-Path $root 'backend/src/main/resources/db/migration/profile-v1.1.1.sql'))
$null=Sql "CREATE DATABASE $schema; USE $schema; $base INSERT INTO department(department_name,base_salary) VALUES('MIGRATION_SENTINEL',1234.56);"
Check ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema';") -eq '12') 'Legacy schema starts with twelve tables'
$first=$migration.Substring(0,$migration.IndexOf(';')+1)
$null=Sql "USE $schema; $first SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='intentional migration interruption'; $migration" -ExpectFailure
Check ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema';") -eq '13') 'Interrupted DDL leaves its first committed table; rollback is not assumed'
$null=Sql "USE $schema; $migration"
Check ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema';") -eq '17') 'Rerun completes all five profile tables after partial failure'
$null=Sql "USE $schema; INSERT INTO employee(employee_no,name,identity_number,birth_date,hire_date) VALUES('990001','Synthetic migration fixture','110101199001010099','1990-01-01','2026-01-01'); INSERT INTO employee_skill(employee_id,name,proficiency) SELECT id,'RECOVERY_SENTINEL','test' FROM employee WHERE employee_no='990001'; $migration"
Check ((Sql "USE $schema; SELECT COUNT(*) FROM employee_skill WHERE name='RECOVERY_SENTINEL';") -eq '1') 'Repeated migration preserves existing profile data'
Check ((Sql "USE $schema; SELECT base_salary FROM department WHERE department_name='MIGRATION_SENTINEL';") -eq '1234.56') 'Legacy business data survives failure, recovery and repeat execution'
[IO.File]::WriteAllText($OutputFile,($checks -join [Environment]::NewLine),(New-Object Text.UTF8Encoding($false)))
# Retain this dedicated schema as evidence; no DROP or real database cleanup occurs.
