param(
    [Parameter(Mandatory=$true)][Alias('MySqlHome')][string]$SourceMySqlHome,
    [Parameter(Mandatory=$true)][string]$VisualCppRedist,
    [Parameter(Mandatory=$true)][string]$VisualCppLicense,
    [string]$SourceHost = '127.0.0.1',
    [int]$SourcePort = 3306,
    [string]$SourceUser = 'root',
    [string]$SourcePassword = $env:EMS_SOURCE_DB_PASSWORD,
    [Parameter(Mandatory=$true)][string]$ContextPath,
    [string]$MavenExe='mvn.cmd'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\portable-common.ps1"
$root = $script:EmsRoot
. "$PSScriptRoot\artifact-common.ps1"
$run=Read-ArtifactContext $root $ContextPath
$cache = Join-Path $root '.release-cache'
$workRoot = $run.path
$work = Join-Path $workRoot 'build'
$stage = Join-Path $work 'EMS_v1.1.3'
$sourceConfig = Join-Path $work 'source-client.cnf'
$stageStarted = $false
$buildState = New-EmsState
$buildLock = $null
try {
    $buildLock = Enter-EmsLock
    $running = Read-EmsState
    if ($running -and ((Get-OwnedProcess $running.app) -or (Get-OwnedProcess $running.database))) { throw 'Stop the portable BAT instance in this source folder before rebuilding.' }
    Mount-EmsNativeRoot $buildState
    foreach ($dir in @($cache, $work, $stage, "$root\runtime\mysql\bin", "$root\runtime\mysql\lib", "$root\app", "$root\bootstrap", "$root\dist")) { [IO.Directory]::CreateDirectory($dir) | Out-Null }
    $lock = Get-Content -LiteralPath "$PSScriptRoot\dependencies-lock.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    $version = Invoke-EmsTool "$SourceMySqlHome\bin\mysqld.exe" @('--version')
    if ($version -notmatch [regex]::Escape("Ver $($lock.mysql.version) ")) { throw "MySQL version must match dependencies-lock.json: $($lock.mysql.version)" }
    foreach ($required in @('mysql.exe', 'mysqld.exe', 'mysqldump.exe')) { Copy-Item -LiteralPath "$SourceMySqlHome\bin\$required" -Destination "$root\runtime\mysql\bin" -Force }
    Get-ChildItem -LiteralPath "$SourceMySqlHome\bin" -Filter '*.dll' | Where-Object { $_.Name -notmatch 'debug' } | Copy-Item -Destination "$root\runtime\mysql\bin" -Force
    Copy-Item -LiteralPath "$SourceMySqlHome\lib\plugin" -Destination "$root\runtime\mysql\lib" -Recurse -Force
    if (Test-Path -LiteralPath "$SourceMySqlHome\lib\private") { Copy-Item -LiteralPath "$SourceMySqlHome\lib\private" -Destination "$root\runtime\mysql\lib" -Recurse -Force }
    if (Test-Path -LiteralPath "$SourceMySqlHome\share") { Copy-Item -LiteralPath "$SourceMySqlHome\share" -Destination "$root\runtime\mysql" -Recurse -Force }
    foreach ($name in @('LICENSE','README','docs')) { if (Test-Path -LiteralPath "$SourceMySqlHome\$name") { Copy-Item -LiteralPath "$SourceMySqlHome\$name" -Destination "$root\runtime\mysql" -Recurse -Force } }
    if (-not (Test-Path -LiteralPath "$VisualCppRedist\vcruntime140.dll")) { throw 'VisualCppRedist must be the x64 Microsoft.VC143.CRT directory.' }
    Get-ChildItem -LiteralPath $VisualCppRedist -Filter '*.dll' | Copy-Item -Destination "$root\runtime\mysql\bin" -Force
    Copy-Item -LiteralPath $VisualCppLicense -Destination "$root\runtime\mysql\VISUAL_CPP_REDIST_LICENSE.txt" -Force
    $archive = Join-Path $cache $lock.java.name
    if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $lock.java.sha256) {
        $download=Join-Path $work 'java-download.zip'
        & curl.exe --ssl-revoke-best-effort -fL --retry 3 --output $download $lock.java.link
        if($LASTEXITCODE -ne 0){throw 'Pinned JRE download failed'}
        if((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $lock.java.sha256){throw 'JRE checksum mismatch'}
        Move-Item -LiteralPath $download -Destination $archive -Force
    }
    Expand-Archive -LiteralPath $archive -DestinationPath "$work\java-unpacked"
    $jre = Get-ChildItem -LiteralPath "$work\java-unpacked" -Directory | Select-Object -First 1
    [IO.Directory]::CreateDirectory("$root\runtime\java") | Out-Null
    Get-ChildItem -LiteralPath $jre.FullName -Force | Copy-Item -Destination "$root\runtime\java" -Recurse -Force

    Write-Host 'Building the portable JAR (development application.yml is excluded)...'
    Push-Location "$root\backend"
    try {
        & $MavenExe -B clean package -Pportable-release -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Maven packaging failed.' }
    } finally { Pop-Location }
    Copy-Item -LiteralPath "$root\backend\target\employee-management-system-1.1.3.jar" -Destination "$root\app\ems-1.1.3.jar" -Force

    [IO.Directory]::CreateDirectory("$root\app\migrations") | Out-Null
    Copy-Item -LiteralPath "$root\backend\src\main\resources\db\migration\profile-v1.1.1.sql" -Destination "$root\app\migrations\profile-v1.1.1.sql" -Force
    if (-not $SourcePassword) {
        $developmentConfig = Get-Content -LiteralPath "$root\backend\src\main\resources\application.yml" -Raw -Encoding UTF8
        $passwordMatch = [regex]::Match($developmentConfig, '(?m)^\s+password:\s*"([^"\r\n]*)"\s*$')
        if (-not $passwordMatch.Success) { throw 'Set EMS_SOURCE_DB_PASSWORD for the source database.' }
        $SourcePassword = $passwordMatch.Groups[1].Value
    }
    if ($SourceUser -notmatch '^[a-zA-Z0-9_]+$' -or $SourceHost -notmatch '^[a-zA-Z0-9.:-]+$') { throw 'Invalid source connection options.' }
    $escapedPassword = $SourcePassword.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n')
    Write-Utf8 $sourceConfig "[client]`r`nhost=$SourceHost`r`nport=$SourcePort`r`nuser=$SourceUser`r`npassword=`"$escapedPassword`"`r`nprotocol=TCP`r`ndefault-character-set=utf8mb4`r`n"
    Write-Host 'Exporting ems_db. Keep source business writes paused during this release.'
    $tables = (Invoke-EmsTool $script:MysqlExe @("--defaults-file=$sourceConfig", '-N', '-B', '-e', "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='ems_db' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) -split '\r?\n'
    $requiredTables=@('department','position_info','employee','user_account','employee_change_record','salary_record','operation_log','dictionary_item','attendance_rule','attendance_record','leave_request','leave_approval_record')
    foreach($requiredTable in $requiredTables) {
        if($tables -notcontains $requiredTable){throw "Missing required EMS table: $requiredTable"}
    }
    $counts = [ordered]@{}
    foreach ($table in $tables) {
        if ($table -notmatch '^[a-z_]+$') { throw 'Unexpected table name.' }
        $counts[$table] = [long](Invoke-EmsTool $script:MysqlExe @("--defaults-file=$sourceConfig", '-N', '-B', 'ems_db', '-e', "SELECT COUNT(*) FROM $table"))
    }
    Invoke-EmsTool "$root\runtime\mysql\bin\mysqldump.exe" @("--defaults-file=$sourceConfig", '--single-transaction', '--quick', '--skip-lock-tables', '--no-tablespaces', '--set-gtid-purged=OFF', '--hex-blob', '--default-character-set=utf8mb4', '--column-statistics=0', "--result-file=$root\bootstrap\snapshot.sql", 'ems_db') -TimeoutSeconds 600 | Out-Null
    foreach ($table in $tables) {
        $after = [long](Invoke-EmsTool $script:MysqlExe @("--defaults-file=$sourceConfig", '-N', '-B', 'ems_db', '-e', "SELECT COUNT(*) FROM $table"))
        if ($after -ne $counts[$table]) { throw 'Source row counts changed during export. Pause writes and rebuild.' }
    }
    Write-Utf8 "$root\bootstrap\snapshot-counts.json" ($counts | ConvertTo-Json)
    Write-Utf8 "$root\bootstrap\snapshot-manifest.json" ([pscustomobject]@{ version='1.1.3'; exportedAt=(Get-Date).ToString('o'); mysqlVersion=$lock.mysql.version; sha256=(Get-FileHash -LiteralPath "$root\bootstrap\snapshot.sql" -Algorithm SHA256).Hash } | ConvertTo-Json)
    # A repeatable inventory of the actual redistributed native binaries.
    $inventory = Get-ChildItem -LiteralPath "$root\runtime" -File -Recurse | ForEach-Object { [pscustomobject]@{ path=$_.FullName.Substring($root.Length+1); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } }
    Write-Utf8 "$root\bootstrap\runtime-sha256.json" ($inventory | ConvertTo-Json -Depth 4)

    foreach ($name in @('runtime','bootstrap')) { Copy-Item -LiteralPath "$root\$name" -Destination $stage -Recurse }
    [IO.Directory]::CreateDirectory("$stage\app\migrations") | Out-Null
    Copy-Item -LiteralPath "$root\app\ems-1.1.3.jar" -Destination "$stage\app"
    Copy-Item -LiteralPath "$root\app\migrations\profile-v1.1.1.sql" -Destination "$stage\app\migrations"
    [IO.Directory]::CreateDirectory("$stage\launcher") | Out-Null
    foreach ($name in @('start-ems.ps1','stop-ems.ps1','portable-common.ps1')) { Copy-Item -LiteralPath "$root\launcher\$name" -Destination "$stage\launcher" }
    foreach ($name in @('启动员工管理系统.bat','停止员工管理系统.bat','便携版使用说明.txt','更新说明_v1.1.3.md','验收记录_v1.1.3.md','THIRD_PARTY.md')) { Copy-Item -LiteralPath "$root\$name" -Destination $stage }
    Write-Host 'Validating first startup and snapshot restore in a separate temporary instance...'
    Register-ArtifactInstance $run $stage
    $stageStarted = $true
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$stage\launcher\start-ems.ps1" -NoBrowser
    if ($LASTEXITCODE -ne 0) { throw 'Release startup/restore validation failed.' }
    $validation = Get-Content -LiteralPath "$stage\data\instance.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    $login = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($validation.httpPort)/login" -TimeoutSec 10
    if ($login.StatusCode -ne 200 -or $login.Content -notmatch 'EMS') { throw 'Release login page validation failed.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$stage\launcher\stop-ems.ps1"
    if ($LASTEXITCODE -ne 0) { throw 'Release shutdown validation failed.' }
    $stageStarted = $false
    # Only disposable validation data under this unique build directory is removed.
    foreach ($name in @('data','logs')) {
        $target = [IO.Path]::GetFullPath((Join-Path $stage $name))
        if (-not $target.StartsWith([IO.Path]::GetFullPath($workRoot) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe validation cleanup path.' }
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    $zip = Join-Path $run.path "candidate.zip"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $newZip = "$zip.new"
    if (Test-Path -LiteralPath $newZip) { Remove-Item -LiteralPath $newZip -Force }
    # Explicit '/' entry separators avoid .NET Framework's legacy Windows ZIP backslashes.
    $outputZip = [IO.Compression.ZipFile]::Open($newZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in (Get-ChildItem -LiteralPath $stage -File -Recurse)) {
            $entryName = 'EMS_v1.1.3/' + $file.FullName.Substring($stage.Length + 1).Replace('\','/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($outputZip, $file.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $outputZip.Dispose() }
    Move-Item -LiteralPath $newZip -Destination $zip -Force
    Write-Utf8 "$zip.sha256" ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash + '  ' + [IO.Path]::GetFileName($zip))
    Write-Host "Release verified and created: $zip"
} catch {$primaryFailure=$_} finally {
    foreach($action in @(
        {if($stageStarted){& powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$stage\launcher\stop-ems.ps1";if($LASTEXITCODE -ne 0){throw 'Stage stop failed'}}},
        {if(Test-Path -LiteralPath $sourceConfig){Remove-Item -LiteralPath $sourceConfig -Force}},
        {Dismount-EmsNativeRoot $buildState},
        {if(Test-Path -LiteralPath "$root\backend\target\classes"){Copy-Item -LiteralPath "$root\backend\src\main\resources\application.yml" -Destination "$root\backend\target\classes\application.yml" -Force}},
        {if($buildLock){$buildLock.Dispose()}}
    )){
        try{& $action}catch{Write-Warning $_.Exception.Message;$finalizerFailure=$_.Exception.Message}
    }
    $SourcePassword=$null
}
if($primaryFailure){throw $primaryFailure}
if($finalizerFailure){throw "Build finalizer failed: $finalizerFailure"}
