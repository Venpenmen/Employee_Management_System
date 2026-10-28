param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$ProjectRoot,[ValidateSet('113','120')][string]$Label)
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath "$Fixture/test-fixture.json")){throw 'Synthetic fixture required'}
. "$Fixture/server/runtime-common.ps1" -Mode server
$lock=$null;$oldUrl=$env:EMS_IT_URL;$oldPassword=$env:EMS_IT_PASSWORD
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;Mount-EmsNativeRoot $s;$secret=Read-EmsSecrets
 $env:EMS_IT_URL="jdbc:mysql://127.0.0.1:$($s.dbPort)/$script:DbName"+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";$env:EMS_IT_PASSWORD=$secret.root
 $cp="$ProjectRoot/backend/target/test-classes;$ProjectRoot/backend/target/classes;"+[IO.File]::ReadAllText("$ProjectRoot/backend/target/dev-classpath.txt").Trim()
 Invoke-EmsTool $script:JavaExe @('-Dfile.encoding=UTF-8','-cp',$cp,'com.example.ems.upgrade.LegacyFixtureMain',$Label) -TimeoutSeconds 120|Write-Host
 Stop-EmsDatabase $s;Save-EmsState $s
 if(Get-OwnedProcess $s.database){throw 'Synthetic source must be stopped before physical copy'}
 $oldData=if($Label -eq '113'){Join-Path $Fixture 'data'}else{Join-Path $Fixture 'data/server'}
 $source=Join-Path $script:DataDir 'mysql-v3';$target=Join-Path $oldData 'mysql'
 Assert-ArtifactPath $Fixture $source|Out-Null;Assert-ArtifactPath $Fixture $target|Out-Null
 if(Test-Path -LiteralPath $target){throw 'Refuse existing legacy fixture'}
 Copy-Item -LiteralPath $source -Destination $target -Recurse
 Write-Utf8 (Join-Path $oldData 'instance.json') (@{version=$(if($Label -eq '113'){'1.1.3'}else{'1.2.0'});app=$null;database=$null;nativeDrive=''}|ConvertTo-Json)
 if($Label -eq '113'){Write-Utf8 (Join-Path $oldData 'credentials.json') (@{root=$secret.root;app=$secret.app}|ConvertTo-Json)}
 else{$wrapped=@{};foreach($key in @('root','app','control')){$wrapped[$key]=ConvertFrom-SecureString (ConvertTo-SecureString $secret.$key -AsPlainText -Force)};Write-Utf8 (Join-Path $oldData 'credentials.dpapi.json') ($wrapped|ConvertTo-Json)}
 Write-Host "PASS: stopped synthetic legacy$Label installation prepared"
}finally{
 $env:EMS_IT_URL=$oldUrl;$env:EMS_IT_PASSWORD=$oldPassword
 Clear-ClientConfigs
 if($s){Dismount-EmsNativeRoot $s;Save-EmsState $s}
 if($lock){$lock.Dispose()}
}
