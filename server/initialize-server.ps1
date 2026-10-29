param([ValidateSet('server','portable','dev')][string]$Mode='server',[switch]$DatabaseOnly)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$lock=$null;$state=$null;$oldEnv=@{}
try{
 $lock=Enter-EmsLock;Assert-Package;$state=Read-EmsState;if(-not $state){$state=New-EmsState}
 if(Get-OwnedProcess $state.app){throw '请先停止正在运行的应用，再执行本地初始化'}
 if(((Test-Path -LiteralPath "$script:EmsRoot/data/mysql") -or (Test-Path -LiteralPath "$script:DataDir/mysql")) -and -not(Test-Path -LiteralPath "$script:DataDir/active-schema")){throw '检测到旧安装，请先通过启动入口隔离升级；不会创建空库替代旧数据'}
 Start-LocalDatabase $state
 if(-not $DatabaseOnly){
  $cnf=Write-ClientConfig $state
  $count=Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N',$script:DbName,'-e','SELECT COUNT(*) FROM user_account')
  if([int]$count -ne 0){throw '数据库中已存在账号，为保护现有数据，禁止重复初始化'}
  Invoke-EmsFirstSetup $state
 }
 if($Mode -eq 'dev'){
  # Protected, local-only IDE environment file; never shipped or synced.
  $secret=Read-EmsSecrets
  Write-Utf8 "$script:DataDir\idea.env" ("EMS_DEV_DB_NAME="+$script:DbName+[Environment]::NewLine+"EMS_DEV_DB_PORT="+$state.dbPort+[Environment]::NewLine+"EMS_DEV_DB_PASSWORD="+$secret.app)
  Write-Host 'IDEA: use data/dev/idea.env as the local environment file in EmsApplication run configuration.'
 }
 Write-Host "[$Mode] 数据库准备完成"
}finally{
 try{
  foreach($key in $oldEnv.Keys){[Environment]::SetEnvironmentVariable($key,$oldEnv[$key],'Process')}
  Clear-ClientConfigs
 }finally{if($lock){$lock.Dispose()}}
}
