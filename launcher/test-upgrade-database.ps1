param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$ProjectRoot)
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath "$Fixture\test-fixture.json")){throw 'Synthetic fixture required'}
. "$Fixture\server\runtime-common.ps1" -Mode server
$lock=$null;$oldUrl=$env:EMS_IT_URL;$oldPass=$env:EMS_IT_PASSWORD
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($s.dbPort)/$script:DbName"+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
 $env:EMS_IT_PASSWORD=(Read-EmsSecrets).root
 $cp="$ProjectRoot\backend\target\test-classes;$ProjectRoot\backend\target\classes;"+[IO.File]::ReadAllText("$ProjectRoot\backend\target\dev-classpath.txt").Trim()
 $work=Get-NativePath (Join-Path $script:DataDir 'it');New-Item -ItemType Directory -Path $work|Out-Null
 Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.UpgradeDatabaseIT',$work) -TimeoutSeconds 300|Write-Host
 }finally{
 if($work -and (Test-Path -LiteralPath $work)){
  $actual=Get-NativePath (Join-Path $script:DataDir 'it')
  if([IO.Path]::GetFullPath($work) -ne [IO.Path]::GetFullPath($actual)){throw 'Unsafe test cleanup'}
  if(@(Get-ChildItem -LiteralPath $work -Recurse -Force|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count){throw 'Test output contains a link'}
  Remove-Item -LiteralPath $work -Recurse -Force
 }
 $env:EMS_IT_URL=$oldUrl;$env:EMS_IT_PASSWORD=$oldPass;Clear-ClientConfigs;if($lock){$lock.Dispose()}}
