param([string]$BackupFile,[ValidateSet('server','portable','dev')][string]$Mode='server',[string]$SourceRoot,[string]$Sha256)
$ErrorActionPreference='Stop';$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
if(-not $BackupFile){$BackupFile=(Read-Host '请输入已导出的 backup.emsbk 完整路径（不需要口令）').Trim('"')}
$file=Get-Item -LiteralPath ([IO.Path]::GetFullPath($BackupFile));if($file.PSIsContainer -or $file.Length -lt 56 -or $file.Length -gt 512MB){throw '备份文件大小无效（上限512MiB）'}
$stream=[IO.File]::OpenRead($file.FullName);try{$header=New-Object byte[] 8;if($stream.Read($header,0,8) -ne 8 -or [Text.Encoding]::ASCII.GetString($header) -ne 'EMSBK001'){throw '不是支持的emsbk加密格式'}}finally{$stream.Dispose()}
if(-not $Sha256){
 if(Test-Path -LiteralPath ($file.FullName+'.sha256')){$Sha256=[IO.File]::ReadAllText($file.FullName+'.sha256').Trim()}
 elseif(Test-Path -LiteralPath (Join-Path $file.DirectoryName 'checksums.sha256')){foreach($line in [IO.File]::ReadAllLines((Join-Path $file.DirectoryName 'checksums.sha256'))){if($line -match ('^([a-fA-F0-9]{64})\s+\*?'+[regex]::Escape($file.Name)+'$')){$Sha256=$Matches[1]}}}
}
if(-not $Sha256 -or $Sha256 -notmatch '^[a-fA-F0-9]{64}$'){throw '需要原始SHA256：保留备份ZIP中的checksums.sha256，或传入-Sha256。校验值不替代口令及真实恢复验证'}
$actual=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash;if($actual -ne $Sha256){throw '备份SHA256不一致，停止升级'}
$required=[long]$file.Length*5+1GB;$available=(New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($root))).AvailableFreeSpace;if($available -lt $required){throw '磁盘空间不足，停止升级；不会清理任何数据'}
$sourceVersion='需在认证导入预览中确认';$sourceState='未指定旧安装，仅检查备份'
if($SourceRoot){
 $source=[IO.Path]::GetFullPath($SourceRoot);$metadata=Assert-ArtifactPath $source (Join-Path $source 'app/version.json');$version=Get-Content -LiteralPath $metadata -Raw -Encoding UTF8|ConvertFrom-Json
 if($version.version -ne '1.2.0' -and $version.buildId -ne 'v1.2.1-next-rc1'){throw '仅适用于已确认的v1.2.0或rc1来源；不会修改旧安装'}
 $sourceVersion=$version.buildId;$state=Assert-ArtifactPath $source (Join-Path $source ("data/"+$Mode+"/instance-upgrade.json"));if(Test-Path -LiteralPath $state){$record=Get-Content -LiteralPath $state -Raw -Encoding UTF8|ConvertFrom-Json;if($record.mode -ne $Mode){throw '旧安装运行模式不一致'};$sourceState=$state}else{throw '没有找到对应模式的旧安装状态，停止自动识别'}
}
@{preflight='passed-not-restored';sourceVersion=$sourceVersion;sourceState=$sourceState;mode=$Mode;sha256=$actual;estimatedRequiredBytes=$required;availableBytes=$available;next='保留旧安装；在新rc2数据库管理页上传emsbk和口令，完成真实隔离恢复、原账号确认及业务核验后，由用户决定切换';databaseConnected=$false}|ConvertTo-Json
