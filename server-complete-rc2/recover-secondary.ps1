param([ValidateSet('server','portable','dev')][string]$Mode='server',[Parameter(Mandatory=$true)][ValidatePattern('^[0-9]{6}$')][string]$TargetEmployeeNo,[Parameter(Mandatory=$true)][ValidatePattern('^[A-Z0-9_-]{8,64}$')][string]$ApprovalReference)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/runtime-common.ps1" -Mode $Mode
$lock=$null;$lease=$null;$state=$null;$startedDatabase=$false;$job=$null;$resultCode=1;$uncertain=$false;$attempted=$false;$confirmedRefusal=$false;$saved=@{}
$names=@('EMS_UPGRADE_ROOT','EMS_UPGRADE_MODE','EMS_BACKUP_PASSWORD','EMS_EXPORT_TARGET_VERSION','EMS_COMPATIBILITY_PREVIEW','EMS_MAINT_ROOT','EMS_MAINT_JOB','EMS_MAINT_URL','EMS_MAINT_USER','EMS_MAINT_PASSWORD','EMS_RECOVERY_TARGET','EMS_RECOVERY_TARGET_PASSWORD','EMS_RECOVERY_REVIEWER','EMS_RECOVERY_REVIEWER_PASSWORD','EMS_RECOVERY_BACKUP_SHA','EMS_RECOVERY_APPROVAL_REFERENCE')
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Put-PrivateSecret([string]$Name,[Security.SecureString]$Secret){$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret);try{[Environment]::SetEnvironmentVariable($Name,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr),'Process')}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr);$Secret.Dispose()}}
function Recovery-State([string]$Value){if($job){Write-Utf8 "$job/status.json" (@{state=$Value;requestId=[IO.Path]::GetFileName($job);approvalReference=$ApprovalReference;automaticRetryAllowed=$false}|ConvertTo-Json)}}
try{
 $lock=Enter-EmsLock;Assert-Package;$state=Read-EmsState
 if(-not $state){throw 'Initialize this complete-candidate mode before requesting recovery'}
 if(Get-OwnedProcess $state.app){throw 'Stop only this complete-candidate application before local recovery'}
 # Share-none also excludes a direct IDEA main() that holds ApplicationLease.
 $lease=[IO.File]::Open("$script:DataDir/application.lease",'OpenOrCreate','ReadWrite','None')
 $startedDatabase=-not [bool](Get-OwnedProcess $state.database)
 if($startedDatabase){Start-LocalDatabase $state}
 Mount-EmsNativeRoot $state
 $cnf=Write-ClientConfig $state
 $executives=[int](Invoke-EmsTool $script:MysqlExe @("--defaults-file=$cnf",'-N',$script:DbName,'-e',"SELECT COUNT(*) FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.user_type=2 AND e.employment_status=1 AND a.account_status=1"))
 if($executives -lt 2){throw 'At least two independent active executive accounts are required; no single-password recovery bypass exists'}
 $id=[guid]::NewGuid().ToString();$job=Join-Path $script:DataDir ('secondary-recovery/'+$id)
 if(Test-Path -LiteralPath $job){throw 'Recovery request collision'}
 [IO.Directory]::CreateDirectory($job)|Out-Null
 Write-Utf8 "$job/request.json" (@{requestId=$id;approvalReference=$ApprovalReference;mode=$Mode;targetEmployeeNo=$TargetEmployeeNo;createdAt=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json)
 $pass=New-EmsSecret
 Write-Utf8 "$job/recovery.key.dpapi" (ConvertFrom-SecureString (ConvertTo-SecureString $pass -AsPlainText -Force))
 $env:EMS_BACKUP_PASSWORD=$pass;$env:EMS_EXPORT_TARGET_VERSION='current';$env:EMS_COMPATIBILITY_PREVIEW='false'
 Recovery-State 'CREATING_RECOVERY_POINT';Invoke-EmsMaintenance 'export' $state $job
 $sha=(Get-FileHash -LiteralPath "$job/backup.emsbk" -Algorithm SHA256).Hash.ToLowerInvariant();Write-Utf8 "$job/backup.emsbk.sha256" $sha
 [IO.Directory]::CreateDirectory("$job/verification")|Out-Null
 Copy-Item -LiteralPath "$job/backup.emsbk" -Destination "$job/verification/input.emsbk"
 Invoke-EmsMaintenance 'restore' $state "$job/verification"
 Recovery-State 'RECOVERY_POINT_VERIFIED'
 Write-Host "恢复请求：$id。加密备份已实际恢复到隔离代际；原业务库尚未修改。"
 Write-Host '需要目标高层及另一名独立有效高层在本机分别验证；密码必须不同。不能完成独立线下核验时请取消。'
 Put-PrivateSecret 'EMS_RECOVERY_TARGET_PASSWORD' (Read-Host '目标高层输入当前登录密码' -AsSecureString)
 $reviewer=(Read-Host '独立复核高层输入六位工号').Trim();if($reviewer -notmatch '^[0-9]{6}$' -or $reviewer -eq $TargetEmployeeNo){throw 'Independent reviewer required'}
 Put-PrivateSecret 'EMS_RECOVERY_REVIEWER_PASSWORD' (Read-Host '独立复核高层输入当前登录密码' -AsSecureString)
 if((Read-Host ("已按审批单核验；输入 APPLY "+$id+" 确认，仅清除目标二级凭证")) -cne ('APPLY '+$id)){Recovery-State 'CANCELLED';throw 'Recovery cancelled; verified backup retained'}
 $secret=Read-EmsSecrets
 $vars=@{EMS_UPGRADE_ROOT=$script:EmsRoot;EMS_UPGRADE_MODE=$Mode;EMS_MAINT_ROOT=$script:EmsRoot;EMS_MAINT_JOB=$job;EMS_MAINT_URL=("jdbc:mysql://127.0.0.1:"+$state.dbPort+"/"+$script:DbName+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=60000");EMS_MAINT_USER='root';EMS_MAINT_PASSWORD=$secret.root;EMS_RECOVERY_TARGET=$TargetEmployeeNo;EMS_RECOVERY_REVIEWER=$reviewer;EMS_RECOVERY_BACKUP_SHA=$sha;EMS_RECOVERY_APPROVAL_REFERENCE=$ApprovalReference}
 foreach($name in $vars.Keys){[Environment]::SetEnvironmentVariable($name,[string]$vars[$name],'Process')}
 Recovery-State 'INDEPENDENT_REVIEW_CONFIRMED'
 # Java must win the same lease before touching source data. If IDEA wins the
 # short handoff race, Java refuses; there is no unlocked recovery mutation.
 $lease.Dispose();$lease=$null
 $attempted=$true
 $code=Invoke-ArtifactNative $script:JavaExe @('-Dloader.main=com.example.ems.upgrade.LocalSecondaryRecoveryMain','-cp',$script:JarFile,'org.springframework.boot.loader.launch.PropertiesLauncher') "$job/recovery.log"
 if($code -eq 2){$uncertain=$true;throw 'Recovery commit acknowledgement uncertain'}
 if($code -ne 0){$confirmedRefusal=-not(Test-Path -LiteralPath "$job/applied.json");throw 'Recovery refused; inspect request diagnostics, do not blindly retry'}
 Recovery-State 'COMMITTED';$resultCode=0
 Write-Host "受控恢复已提交：$id。登录密码和角色未改；目标须重新登录并设置二级密码。"
}catch{
 if($attempted -and -not $confirmedRefusal){$uncertain=$true}
 if($uncertain){Recovery-State 'REQUIRES_AUDIT_VERIFICATION';$resultCode=2}else{if($job -and -not(Test-Path -LiteralPath "$job/applied.json")){Recovery-State 'REFUSED_OR_CANCELLED'}}
 Write-Host ('本机恢复未正常结束；保留恢复点及请求，禁止自动重试。原因类型：'+$_.Exception.GetType().Name)
}finally{
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 try{
  if($startedDatabase -and $state){
   if(-not $lease){$lease=[IO.File]::Open("$script:DataDir/application.lease",'OpenOrCreate','ReadWrite','None')}
   $latest=Read-EmsState;if(Get-OwnedProcess $latest.app){throw 'Another owned application started; database retained'}
   Stop-EmsDatabase $latest;Dismount-EmsNativeRoot $latest;Save-EmsState $latest
  }
 }catch{Write-Host '本候选数据库保留运行；无法证明独占清理安全，不强制停止。';if($resultCode -eq 0){$resultCode=2}}
 Clear-ClientConfigs;if($lease){$lease.Dispose()};if($lock){$lock.Dispose()}
}
exit $resultCode
