param([string]$Action,[string]$CopyId,[string]$BaseFile,[string]$ExtensionFile,[string]$LegacyZip)
# Synthetic test adapter only: never used by the user-facing tool to collect credentials.
$arguments=@{Action=$Action}
if($Action -eq 'Create'){$arguments.BaseFile=$BaseFile;$arguments.ExtensionFile=$ExtensionFile;$arguments.LegacyZip=$LegacyZip;$arguments.BackupPassword=ConvertTo-SecureString 'SyntheticControlledRoundtrip91' -AsPlainText -Force}else{$arguments.CopyId=$CopyId}
$global:LASTEXITCODE=0
& "$PSScriptRoot/controlled-legacy-copy.ps1" @arguments
if(-not $?){exit 1}
