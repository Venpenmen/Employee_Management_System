param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$ProjectRoot)
$ErrorActionPreference='Stop'
. "$Fixture\server\runtime-common.ps1" -Mode server
if(-not(Test-Path -LiteralPath "$Fixture\test-fixture.json")){throw 'Not a synthetic fixture'}
$lock=$null;$old=@{}
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s;$secret=Read-EmsSecrets
 foreach($key in @('EMS_IT_URL','EMS_DB_PASSWORD')){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($s.dbPort)/$script:DbName"+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
 $env:EMS_DB_PASSWORD=$secret.app
 $cp="$ProjectRoot\backend\target\test-classes;$ProjectRoot\backend\target\classes;"+[IO.File]::ReadAllText("$ProjectRoot\backend\target\dev-classpath.txt").Trim()
 Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.session.SessionDatabaseIT') -TimeoutSeconds 180|Write-Host
 Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.session.WebSessionIT') -TimeoutSeconds 180|Write-Host
}finally{foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')};Clear-ClientConfigs;if($lock){$lock.Dispose()}}
