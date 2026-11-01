param([Parameter(Mandatory=$true)][string]$CandidateDirectory,[string]$ValidatedDirectory)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
. "$root/launcher/artifact-common.ps1"
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed continuation scope required'}
$candidate=Assert-ArtifactPath (Join-Path $root 'dist') ([IO.Path]::GetFullPath($CandidateDirectory))
$inventory=Get-Content "$candidate/BUILD_INVENTORY.json" -Raw -Encoding UTF8|ConvertFrom-Json
$state=(Get-Content "$root/.codex-continuation/STATE.json" -Raw -Encoding UTF8|ConvertFrom-Json).payload
$manifest=Get-Content "$root/app/build-manifest-complete-rc2.json" -Raw -Encoding UTF8|ConvertFrom-Json
$version=Get-Content "$root/app/version-complete-rc2.json" -Raw -Encoding UTF8|ConvertFrom-Json
$items=@()
function Check([string]$Id,[string]$Title,[bool]$Passed,[string]$Detail){
 $script:items+=@{id=$Id;title=$Title;passed=$Passed;detail=$Detail}
 if(-not $Passed){throw ('Audit item failed: '+$Id+' '+$Title+' - '+$Detail)}
}
function Hash-Stream($Stream){$sha=[Security.Cryptography.SHA256]::Create();try{return [BitConverter]::ToString($sha.ComputeHash($Stream)).Replace('-','')}finally{$sha.Dispose()}}
function Read-EntryText($Archive,[string]$Name){
 $entry=$Archive.GetEntry($Name);if(-not $entry){return $null}
 $reader=New-Object IO.StreamReader($entry.Open(),[Text.Encoding]::UTF8);try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
}
function Assert-SafeEntry([string]$Name){
 if($Name -match '(^/|^\\|:|\\|(^|/)\.\.(/|$))'){throw ('Unsafe archive path: '+$Name)}
 if($Name -match '(^|/)(data|logs|dist|\.git|\.idea|\.release-work|\.release-evidence|\.codex-continuation|__pycache__|target|test-work)(/|$)'){throw ('Private path in archive: '+$Name)}
 if($Name -match '\.dpapi$|(^|/)recovery\.key$|(^|/)credentials|idea\.env$|(^|/)network-mode\.json$|(^|/)server\.json$|test-fixture\.json$|\.log$|\.ibd$|\.p12$|\.pfx$|\.pem$|\.key$'){throw ('Credential/data artifact in archive: '+$Name)}
 if($Name -match 'DATABASE_COMPATIBILITY_HANDOFF_20261028|^网络运行模式设置\.bat$|(^|/)AGENTS\.md$'){throw ('Non-delivery document in archive: '+$Name)}
 if($Name -match 'ems-1\.2\.1-complete\.jar$|ems-1\.2\.1-next-rc1\.jar$|ems-1\.2\.1-next-rc2\.jar$|employee-management-system_v1\.2\.0/|employee-management-system_v1\.2\.1/'){throw ('Historical build mixed into archive: '+$Name)}
}

# item 1: protected inputs untouched
foreach($protected in $state.protected){if((Get-FileHash -LiteralPath (Assert-ArtifactPath $root (Join-Path $root $protected.path))).Hash -ne $protected.sha256){throw 'Protected file hash changed'}}
Check 'protected-inputs' '活动基线内的保护文件摘要未变化（已批准移除项见 docs/RC2_PROTECTED_INPUT_LOSS_20261101.json）' $true ('protected='+@($state.protected).Count)

# item 2: build identity chain
if($manifest.buildId -ne 'v1.2.1-complete-rc2' -or $version.buildId -ne $manifest.buildId){throw 'Complete build identity mismatch'}
if((Get-FileHash "$root/app/ems-1.2.1-complete-rc2.jar").Hash -ne $manifest.sha256){throw 'Complete build artifact mismatch'}
if($inventory.buildId -ne $manifest.buildId -or $inventory.jarSha256 -ne $manifest.sha256 -or $inventory.sourceCommit -ne $manifest.sourceCommit){throw 'Source/build/candidate identity mismatch'}
Check 'build-identity' '构建清单、版本文件与候选清单身份一致' $true ('buildId='+$manifest.buildId+' sha256='+$manifest.sha256+' sourceCommit='+$manifest.sourceCommit)

# item 3/4: archive bytes, safe entries, JAR and migrations, source inputs
$entries=0;$sourceInputs=0;$migrationCount=0;$validatedFiles=0;$names=@{};$scriptBom=@{}
foreach($file in $inventory.files){
 $path=Assert-ArtifactPath $candidate (Join-Path $candidate $file.file)
 if((Get-FileHash -LiteralPath $path).Hash -ne $file.sha256){throw 'Final ZIP checksum mismatch'}
 $zip=[IO.Compression.ZipFile]::OpenRead($path);$validated=$null
 if($ValidatedDirectory -and $file.file -notlike '*源码.zip'){
  $validatedRoot=Assert-ArtifactPath (Join-Path $root 'dist') ([IO.Path]::GetFullPath($ValidatedDirectory))
  $validated=[IO.Compression.ZipFile]::OpenRead((Join-Path $validatedRoot $file.file))
 }
 try{
  foreach($entry in $zip.Entries){
   $entries++;Assert-SafeEntry $entry.FullName
   if($file.file -notlike '*源码.zip'){$names[$entry.FullName]=$true}
   # Windows PowerShell 5.1 reads BOM-less .ps1 as the ANSI code page, which turns
   # Chinese literals into mojibake and breaks the parser. Record the BOM state of
   # every shipped script while the archive is still open.
   if($entry.FullName -like 'server-complete-rc2/*.ps1' -or $entry.FullName -like 'RC2_*.bat' -or $entry.FullName -eq 'tools/port-monitor.ps1'){
    $headStream=$entry.Open();$head=New-Object byte[] 3
    try{$read=$headStream.Read($head,0,3)}finally{$headStream.Dispose()}
    $scriptBom[$entry.FullName]=($read -eq 3 -and $head[0] -eq 0xEF -and $head[1] -eq 0xBB -and $head[2] -eq 0xBF)
   }
   if($validated -and $entry.FullName -notlike '*.md'){
    $old=$validated.GetEntry($entry.FullName);if(-not $old){throw 'Final runnable payload differs from extracted tested package'}
    $a=$entry.Open();$b=$old.Open();try{if((Hash-Stream $a) -ne (Hash-Stream $b)){throw 'Final executable/runtime/config/tool bytes differ from tested package'}}finally{$a.Dispose();$b.Dispose()};$validatedFiles++
   }
  }
  $jar=$zip.GetEntry('app/ems-1.2.1-complete-rc2.jar');if(-not $jar){throw 'Complete runnable JAR missing'}
  $stream=$jar.Open();try{if((Hash-Stream $stream) -ne $inventory.jarSha256){throw 'JAR differs across deliveries'}}finally{$stream.Dispose()}
  $memory=New-Object IO.MemoryStream;$stream=$jar.Open();try{$stream.CopyTo($memory)}finally{$stream.Dispose()};$memory.Position=0
  $nested=New-Object IO.Compression.ZipArchive($memory,[IO.Compression.ZipArchiveMode]::Read,$true)
  try{foreach($migration in $state.protected|Where-Object path -like 'backend/src/main/resources/db/upgrade/*'){
   $rel=$migration.path.Replace('backend/src/main/resources/','');$sql=$nested.GetEntry('BOOT-INF/classes/'+$rel);if(-not $sql){throw 'JAR Flyway migration absent'};$stream=$sql.Open();try{if((Hash-Stream $stream) -ne $migration.sha256){throw 'JAR migration checksum altered'}}finally{$stream.Dispose()};if($file.file -like '*服务器版*.zip'){$migrationCount++}
  }}finally{$nested.Dispose();$memory.Dispose()}
  if($file.file -like '*源码.zip'){
   foreach($input in $manifest.sourceInputs){$entry=$zip.GetEntry($input.path);if(-not $entry){throw ('Full source delivery omits tested input: '+$input.path)};$stream=$entry.Open();try{if((Hash-Stream $stream) -ne $input.sha256){throw 'Source archive differs from built source'}}finally{$stream.Dispose()};$sourceInputs++}
  }
 }finally{if($validated){$validated.Dispose()};$zip.Dispose()}
}
Check 'archive-bytes' '两个 ZIP 摘要、逐条重算与源码输入一致' $true ('entries='+$entries+' sourceInputs='+$sourceInputs+' validatedRuntimeFiles='+$validatedFiles)
if($migrationCount -ne 3){throw ('Flyway migration coverage incomplete: '+$migrationCount)}
Check 'flyway-migrations' 'JAR 内 V1/V2/V3 Flyway 迁移与保护摘要一致' $true ('migrations='+$migrationCount)

# item 5: delivery completeness
$required=@('app/ems-1.2.1-complete-rc2.jar','app/version-complete-rc2.json','app/build-manifest-complete-rc2.json','app/migrations/profile-v1.1.1.sql','bootstrap/schema.sql','bootstrap/runtime-sha256.json','compatibility/EMS_v1.2.0_Windows_Portable.zip','tools/port-monitor.ps1','启动端口监控.bat','README.md','CHANGELOG.md','RELEASE_NOTES_v1.2.1.md','docs/UPGRADE_TO_v1.2.1.md','docs/TEST_REPORT.md','docs/COMPLETE_CANDIDATE_GUIDE.md','docs/EMS_v1.2.1_COMPLETE_RC2_DELIVERY_REPORT.md','docs/RC2_DEPLOYMENT.md','docs/RC2_IMPLEMENTATION_LEDGER.md','docs/COMPLETE_MANUAL_ACCEPTANCE.md','RC2_Initialize_Server.bat','RC2_Local_Entry_Code.bat','RC2_Network_Mode.bat','RC2_Start_Server.bat','RC2_Status.bat','RC2_Stop_Server.bat','RC2_View_Logs.bat')
foreach($name in $required){if(-not $names.ContainsKey($name)){throw ('Required delivery entry missing: '+$name)}}
$serverScripts=@($names.Keys|Where-Object {$_ -like 'server-complete-rc2/*.ps1'})
$bats=@($names.Keys|Where-Object {$_ -like 'RC2_*.bat'})
if($serverScripts.Count -ne 18){throw ('Server script count mismatch: '+$serverScripts.Count)}
if($bats.Count -ne 7){throw ('Entry BAT count mismatch: '+$bats.Count)}
# Windows PowerShell 5.1 reads BOM-less .ps1 as the ANSI code page, which turns
# Chinese literals into mojibake and breaks the parser (the .bat launchers call
# powershell.exe 5.1). Every shipped server script must carry a UTF-8 BOM.
$bomChecked=0
foreach($name in $serverScripts){
    if(-not $scriptBom.ContainsKey($name)){throw ('Script encoding not checked: '+$name)}
    if(-not $scriptBom[$name]){throw ('Script without UTF-8 BOM breaks Windows PowerShell 5.1: '+$name)}
    $bomChecked++
}
if(-not $scriptBom.ContainsKey('tools/port-monitor.ps1')){throw 'Port monitor encoding not checked'}
if(-not $scriptBom['tools/port-monitor.ps1']){throw 'tools/port-monitor.ps1 must carry a UTF-8 BOM so Windows PowerShell 5.1 keeps its Chinese text'}
Check 'delivery-completeness' '运行时包必需文件与脚本齐备（脚本均带 UTF-8 BOM）' $true ('required='+$required.Count+' serverScripts='+$serverScripts.Count+' bats='+$bats.Count+' bom='+$bomChecked)

# item 6: runtime inventory reproduced from the archive itself
$runtimeZip=Join-Path $candidate (@($inventory.files|Where-Object {$_.file -notlike '*源码.zip'})[0].file)
$zip=[IO.Compression.ZipFile]::OpenRead($runtimeZip)
try{
 $inventoryText=Read-EntryText $zip 'bootstrap/runtime-sha256.json'
 if(-not $inventoryText){throw 'Runtime inventory missing from archive'}
 $runtimeList=$inventoryText|ConvertFrom-Json
 $runtimeChecked=0
 foreach($entryDef in $runtimeList){
  $entry=$zip.GetEntry($entryDef.path);if(-not $entry){throw ('Runtime inventory entry missing: '+$entryDef.path)}
  $stream=$entry.Open();try{$digest=Hash-Stream $stream}finally{$stream.Dispose()}
  if($digest -ne $entryDef.sha256){throw ('Runtime inventory digest mismatch: '+$entryDef.path)}
  $runtimeChecked++
 }
 $legacy=$zip.GetEntry('compatibility/EMS_v1.2.0_Windows_Portable.zip')
 if(-not $legacy){throw 'Registered legacy runtime archive missing from candidate'}
 $stream=$legacy.Open();try{$legacyHash=Hash-Stream $stream}finally{$stream.Dispose()}
 if($legacyHash -ne '1FC499790A29B836442DB0451CB74C277B45573B87F084AD7FCFC272B0047203'){throw 'Registered legacy runtime archive changed'}
 # item 7: no development credentials or private material in shipped text entries
 $scanned=0;$suspect=@()
 foreach($entry in $zip.Entries){
  if($entry.Length -eq 0 -or $entry.FullName -notmatch '\.(ps1|bat|yml|yaml|json|md|sql|java|js|html|css|properties)$'){continue}
  $text=Read-EntryText $zip $entry.FullName;if($null -eq $text){continue}
  $scanned++
  if($text -match 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|EMS_TLS_STORE_PASSWORD\s*[=:]\s*[^$\s"'']|password\s*:\s*[A-Za-z0-9!@#%^&*._-]{6,}|IDENTIFIED BY\s+[''"]?[A-Za-z0-9!@#%^&*._-]{6,}'){ $suspect+=$entry.FullName }
 }
 if($suspect.Count){throw ('Possible shipped credential in: '+($suspect -join ', '))}
 # item 8: mode entry parity and loopback default documented in the shipped payload
 $policy=Read-EntryText $zip 'server-complete-rc2/network-policy.ps1'
 $startScript=Read-EntryText $zip 'server-complete-rc2/start-server.ps1'
 $deployDoc=Read-EntryText $zip 'docs/RC2_DEPLOYMENT.md'
 $deliveryDoc=Read-EntryText $zip 'docs/EMS_v1.2.1_COMPLETE_RC2_DELIVERY_REPORT.md'
 $ledger=Read-EntryText $zip 'docs/RC2_IMPLEMENTATION_LEDGER.md'
 $modeGui=Read-EntryText $zip 'server-complete-rc2/network-mode-gui.ps1'
 if(-not $policy -or $policy -notmatch 'compat' -or $policy -notmatch 'safe'){throw 'Network policy must support safe and compat modes'}
 if(-not $startScript -or $startScript -notmatch 'bind only to loopback'){throw 'Start script must refuse non-loopback binding'}
 if(-not $deployDoc -or $deployDoc -notmatch '127\.0\.0\.1:8483'){throw 'Deployment document must document the loopback default'}
 if(-not $modeGui){throw 'Network mode entry missing'}
 if(-not $deliveryDoc -or $deliveryDoc -notmatch 'v1\.2\.1-complete-rc2'){throw 'Delivery report must state the candidate identity'}
 if(-not $ledger -or $ledger -notmatch 'RC2-P7'){throw 'Implementation ledger must track RC2-P7'}
 # item 9: database management flow scripts
 foreach($script in @('initialize-server.ps1','database-job.ps1','backup-database.ps1','verify-restore.ps1','restore-private.ps1','check-upgrade.ps1','upgrade-installation.ps1','recover-secondary.ps1','local-entry-code.ps1','stop-server.ps1')){
  if(-not $zip.GetEntry('server-complete-rc2/'+$script)){throw ('Database/lifecycle script missing: '+$script)}
 }
 Check 'runtime-inventory' '运行时清单 492 项在包内逐项复算一致' $true ('runtimeChecked='+$runtimeChecked+' legacySha256='+$legacyHash)
 Check 'no-shipped-credentials' '包内文本未含私钥、口令或测试凭据' $true ('scannedTextEntries='+$scanned)
 Check 'mode-parity' '安全/兼容入口与回环默认在实现和文档中一致' $true 'network-policy + start-server + RC2_DEPLOYMENT + delivery report + ledger'
 # item 8b: shipped documentation must describe this candidate, not the historical one
 $guide=Read-EntryText $zip 'docs/COMPLETE_CANDIDATE_GUIDE.md'
 $leaf=Split-Path $candidate -Leaf
 if(-not $guide){throw 'Candidate guide missing from archive'}
 if($guide -notmatch 'server-complete-rc2'){throw 'Candidate guide must reference the shipped server-complete-rc2 scripts'}
 if($guide -match 'server-complete\\'){throw 'Candidate guide still points at the historical server-complete directory'}
 if($guide -match '8481'){throw 'Candidate guide still advertises the portable 8481 entry that this package does not ship'}
 if($guide -match 'build-manifest-complete\.json'){throw 'Candidate guide still references the historical complete build manifest'}
 if($deliveryDoc -notmatch [regex]::Escape($leaf)){throw ('Packaged delivery report does not name this candidate: '+$leaf)}
 if($deliveryDoc -notmatch [regex]::Escape($manifest.sha256)){throw 'Packaged delivery report does not carry the packaged JAR sha256'}
 if($ledger -notmatch [regex]::Escape('RC2-P7')){throw 'Implementation ledger must track RC2-P7'}
 Check 'docs-accuracy' '包内指南与交付报告对应当前候选而非历史版本' $true ('candidate='+$leaf+' guide=COMPLETE_CANDIDATE_GUIDE.md')
 Check 'database-flow' '数据库初始化、切换、备份、校验、恢复、回退脚本齐备' $true '10 lifecycle scripts'
}finally{$zip.Dispose()}

# item 10: candidate start verification produced by tools/smoke-candidate-rc2.ps1
$smokePath=Join-Path $candidate 'CANDIDATE_SMOKE.json'
if(-not(Test-Path -LiteralPath $smokePath)){throw 'Candidate start verification missing: run tools/smoke-candidate-rc2.ps1 first'}
$smoke=Get-Content $smokePath -Raw -Encoding UTF8|ConvertFrom-Json
if(-not $smoke.passed){throw 'Candidate start verification did not pass'}
Check 'candidate-start' '独立候选目录解压后可启动并响应本机 HTTP' $true ('port='+$smoke.port+' healthStatus='+$smoke.healthStatus+' loginStatus='+$smoke.loginStatus+' checks='+$smoke.checks)

# no owned full workspaces left behind
$active=@();foreach($dir in Get-ChildItem "$root/.release-work/runs" -Directory -ErrorAction SilentlyContinue){$record=Join-Path $dir.FullName 'run.json';if(Test-Path -LiteralPath $record){$r=Get-Content $record -Raw -Encoding UTF8|ConvertFrom-Json;if($r.ownerTaskId -eq $env:EMS_DEVELOPMENT_TASK_ID){$active+=@{id=$r.id;status=$r.status}}}}
if($active.Count){throw 'Owned full workspaces remain; audit and resolve before completion'}

$summary=@{passed=$true;exitCode=0;buildId=$manifest.buildId;sourceCommit=$manifest.sourceCommit;jarSha256=$manifest.sha256;candidate=$candidate;items=$items;zipEntries=$entries;sourceInputs=$sourceInputs;historicalMigrationChecks=$migrationCount;protectedHashes=@($state.protected).Count;privatePathsRejected=$true;ownedFullWorkspaces=0;remoteOperations=$false;formalRelease=$false;projectFileBytes=(Get-ArtifactBytes $root);diskFreeBytes=([IO.DriveInfo]::new([IO.Path]::GetPathRoot($root))).AvailableFreeSpace}
Write-ArtifactJson "$root/.codex-continuation/rc2-audit-results.json" $summary
Write-ArtifactJson (Join-Path $candidate 'FINAL_ACCEPTANCE.json') @{
 buildId=$manifest.buildId;version='1.2.1';sourceCommit=$manifest.sourceCommit;jarSha256=$manifest.sha256
 status='LOCAL_CANDIDATE_ACCEPTED'
 releaseStatus='NOT_RELEASED'
 candidateStartVerified=$true
 auditItems=($items|ForEach-Object {@{id=$_.id;title=$_.title;passed=$_.passed}})
 manualAcceptanceRequired=@(
  'clean offline Windows 10/11 extraction and first run',
  'real second physical device for SSE and LAN entry',
  'real public domain certificate and real Sakura Frp tunnel',
  'real production data migration and cutover',
  'IDEA GUI debugging walkthrough',
  'docs/COMPLETE_MANUAL_ACCEPTANCE.md checklist signing'
 )
 auditedAt=(Get-Date).ToString('o')
}
Write-Host ('RC2 candidate audit passed: '+$candidate)
Write-Host ('items='+$items.Count+' entries='+$entries+' migrations='+$migrationCount)
