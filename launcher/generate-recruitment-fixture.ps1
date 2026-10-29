param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$ProjectRoot)
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath "$Fixture/test-fixture.json")){throw 'Synthetic fixture required'}
. "$Fixture/server/runtime-common.ps1" -Mode server
$cp="$ProjectRoot/backend/target/test-classes;$ProjectRoot/backend/target/classes;"+[IO.File]::ReadAllText("$ProjectRoot/backend/target/dev-classpath.txt").Trim()
Invoke-EmsTool $script:JavaExe @('-cp',$cp,'com.example.ems.upgrade.GenerateRecruitmentWorkbook',"$Fixture/data/server/recruitment-private.xlsx")|Write-Host
