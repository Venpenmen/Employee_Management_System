param([ValidateSet('server','portable','dev')][string]$Mode='server')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\runtime-common.ps1" -Mode $Mode
$lock=$null;$prior=$env:EMS_BACKUP_PASSWORD
try{
 $lock=Enter-EmsLock;$s=Read-EmsState;if(-not $s -or -not(Get-OwnedProcess $s.database)){throw 'Owned database must be running'}
 Mount-EmsNativeRoot $s
 $dir=Join-Path $script:DataDir ('backups/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N'))
 New-Item -ItemType Directory -Path $dir -Force|Out-Null
 $pass=New-EmsSecret;Write-Utf8 (Join-Path $dir 'recovery.key.dpapi') (ConvertFrom-SecureString (ConvertTo-SecureString $pass -AsPlainText -Force))
 $env:EMS_BACKUP_PASSWORD=$pass;Invoke-EmsMaintenance 'export' $s $dir
 $file=Join-Path $dir 'backup.emsbk';Write-Utf8 ($file+'.sha256') (Get-FileHash -LiteralPath $file).Hash
 Write-Host "Encrypted backup created: $file";Write-Output $file
}finally{$env:EMS_BACKUP_PASSWORD=$prior;Clear-ClientConfigs;if($lock){$lock.Dispose()}}
