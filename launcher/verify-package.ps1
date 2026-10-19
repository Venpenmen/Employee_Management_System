param([Parameter(Mandatory=$true)][string]$ZipPath,[string]$ProjectRoot,[string]$OutputFile)
$ErrorActionPreference='Stop'
if(-not $ProjectRoot){$ProjectRoot=Split-Path -Parent $PSScriptRoot}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Read-ZipText($Archive,[string]$Name){
    $entry=$Archive.GetEntry($Name);if(-not $entry){throw "Missing ZIP entry: $Name"}
    $reader=New-Object IO.StreamReader($entry.Open(),[Text.Encoding]::UTF8)
    try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
}
function Hash-ZipEntry($Entry){
    $stream=$Entry.Open();$sha=[Security.Cryptography.SHA256]::Create()
    try{return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')}finally{$stream.Dispose();$sha.Dispose()}
}
$version=([xml][IO.File]::ReadAllText((Join-Path $ProjectRoot 'backend/pom.xml'))).project.version
$prefix="EMS_v$version/"
$zip=[IO.Compression.ZipFile]::OpenRead($ZipPath)
$results=New-Object Collections.Generic.List[string]
try{
    $top=@('启动员工管理系统.bat','停止员工管理系统.bat','便携版使用说明.txt',"更新说明_v$version.md","验收记录_v$version.md",'THIRD_PARTY.md')
    foreach($entry in $zip.Entries){
        $relative=$entry.FullName.Substring([Math]::Min($prefix.Length,$entry.FullName.Length))
        $allowed=$relative -in $top -or $relative -in @("app/ems-$version.jar",'app/migrations/profile-v1.1.1.sql','launcher/start-ems.ps1','launcher/stop-ems.ps1','launcher/portable-common.ps1','bootstrap/snapshot.sql','bootstrap/snapshot-counts.json','bootstrap/snapshot-manifest.json','bootstrap/runtime-sha256.json') -or $relative.StartsWith('runtime/java/') -or $relative.StartsWith('runtime/mysql/')
        if(-not $allowed){throw "Entry is outside the release whitelist: $relative"}
        if(-not $entry.FullName.StartsWith($prefix) -or $entry.FullName.Contains('\') -or ($entry.FullName -split '/') -contains '..'){throw 'Unexpected ZIP path'}
        if($entry.FullName -match '/(data|logs|\.git|\.idea|\.release-work|\.release-cache|target|backend|frontend)/|source-client\.cnf|launcher\.lock|instance\.json|\.zip$'){throw 'Private entry in release ZIP'}
    }
    foreach($name in @('启动员工管理系统.bat','停止员工管理系统.bat','便携版使用说明.txt',"更新说明_v$version.md","验收记录_v$version.md",'THIRD_PARTY.md',"app/ems-$version.jar",'app/migrations/profile-v1.1.1.sql','runtime/java/bin/java.exe','runtime/mysql/bin/mysql.exe','runtime/mysql/bin/mysqld.exe','runtime/mysql/bin/vcruntime140.dll','runtime/mysql/VISUAL_CPP_REDIST_LICENSE.txt')){
        if(-not $zip.GetEntry($prefix+$name)){throw "Missing release component: $name"}
    }
    $results.Add('ZIP paths and portable whitelist passed; no used data or development credentials')
    $jarEntry=$zip.GetEntry($prefix+"app/ems-$version.jar")
    if((Hash-ZipEntry $jarEntry) -ne (Get-FileHash -LiteralPath (Join-Path $ProjectRoot "app/ems-$version.jar") -Algorithm SHA256).Hash){throw 'JAR differs from built application'}
    $memory=New-Object IO.MemoryStream;$stream=$jarEntry.Open()
    try{$stream.CopyTo($memory)}finally{$stream.Dispose()}
    $memory.Position=0
    $jar=New-Object IO.Compression.ZipArchive($memory,[IO.Compression.ZipArchiveMode]::Read)
    try{
        $config=Read-ZipText $jar 'BOOT-INF/classes/application.yml'
        if($config -match 'datasource:|password:|jdbc:mysql'){throw 'Development configuration was packaged'}
        if((Read-ZipText $jar 'META-INF/MANIFEST.MF') -notmatch ("Implementation-Version: "+[regex]::Escape($version))){throw 'Wrong packaged version'}
        $results.Add('JAR version and portable configuration passed')
        $oldPath=Join-Path $ProjectRoot 'dist/EMS员工管理系统_v1.1.2_Windows_x64.zip'
        if(Test-Path -LiteralPath $oldPath){
            $oldZip=[IO.Compression.ZipFile]::OpenRead($oldPath)
            $oldMemory=New-Object IO.MemoryStream
            try{
                $oldStream=$oldZip.GetEntry('EMS_v1.1.2/app/ems-1.1.2.jar').Open()
                try{$oldStream.CopyTo($oldMemory)}finally{$oldStream.Dispose()}
                $oldMemory.Position=0
                $oldJar=New-Object IO.Compression.ZipArchive($oldMemory,[IO.Compression.ZipArchiveMode]::Read)
                try{
                    foreach($name in @('SalaryService','AttendanceService','LeaveService')){
                        $class='BOOT-INF/classes/com/example/ems/service/'+$name+'.class'
                        if((Hash-ZipEntry ($oldJar.GetEntry($class))) -ne (Hash-ZipEntry ($jar.GetEntry($class)))){throw "Business calculation bytecode changed: $name"}
                    }
                }finally{$oldJar.Dispose()}
            }finally{$oldMemory.Dispose();$oldZip.Dispose()}
            $results.Add('Salary, attendance and leave calculation bytecode is identical to v1.1.2')
        }

    }finally{$jar.Dispose();$memory.Dispose()}
    $counts=(Read-ZipText $zip ($prefix+'bootstrap/snapshot-counts.json'))|ConvertFrom-Json
    foreach($table in @('department','position_info','employee','user_account','employee_change_record','salary_record','operation_log','dictionary_item','attendance_rule','attendance_record','leave_request','leave_approval_record')){if(-not $counts.PSObject.Properties[$table]){throw "Missing required snapshot table: $table"}}
    $inventory=(Read-ZipText $zip ($prefix+'bootstrap/runtime-sha256.json'))|ConvertFrom-Json
    foreach($file in $inventory){
        $entry=$zip.GetEntry($prefix+$file.path.Replace('\','/'))
        if(-not $entry -or (Hash-ZipEntry $entry) -ne $file.sha256){throw "Runtime hash mismatch: $($file.path)"}
    }
    if((Hash-ZipEntry ($zip.GetEntry($prefix+'app/migrations/profile-v1.1.1.sql'))) -ne (Get-FileHash -LiteralPath (Join-Path $ProjectRoot 'backend/src/main/resources/db/migration/profile-v1.1.1.sql') -Algorithm SHA256).Hash){throw 'Historical migration mismatch'}
    $results.Add('Snapshot tables, historical migration and all runtime hashes passed')
}finally{$zip.Dispose()}
if($OutputFile){[IO.File]::WriteAllText($OutputFile,($results -join [Environment]::NewLine),(New-Object Text.UTF8Encoding($false)))}
$results|ForEach-Object{Write-Host "PASS: $_"}
