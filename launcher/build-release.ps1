param(
    [Parameter(Mandatory=$true)][Alias('MySqlHome')][string]$SourceMySqlHome,
    [Parameter(Mandatory=$true)][string]$VisualCppRedist,
    [Parameter(Mandatory=$true)][string]$VisualCppLicense,
    [string]$SourceHost='127.0.0.1',[int]$SourcePort=3306,[string]$SourceUser='root',
    [string]$SourcePassword=$env:EMS_SOURCE_DB_PASSWORD,[string]$AdminPassword='Admin123',
    [string]$MavenExe,[string]$JavaExe,[string]$NodeExe,[string]$BrowserExe,
    [switch]$KeepArtifacts
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\artifact-common.ps1"
$run=New-ArtifactRun $root 'release' -KeepArtifacts:$KeepArtifacts
$success=$false;$errorText=''
$oldSource=$env:EMS_SOURCE_DB_PASSWORD;$oldAdmin=$env:EMS_TEST_ADMIN_PASSWORD
$shell="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
try{
    $MavenExe=Resolve-ArtifactTool $MavenExe 'mvn.cmd'
    $JavaExe=Resolve-ArtifactTool $JavaExe 'java.exe'
    $NodeExe=Resolve-ArtifactTool $NodeExe 'node.exe'
    if(-not $BrowserExe){$BrowserExe=@("$env:ProgramFiles\Google\Chrome\Application\chrome.exe",(Join-Path ([Environment]::GetEnvironmentVariable('ProgramFiles(x86)')) 'Microsoft/Edge/Application/msedge.exe'))|Where-Object{Test-Path -LiteralPath $_}|Select-Object -First 1}
    if(-not $BrowserExe){throw 'Supply the Chrome or Edge executable using -BrowserExe'}
    $BrowserExe=Resolve-ArtifactTool $BrowserExe 'chrome.exe'
    $env:EMS_SOURCE_DB_PASSWORD=$SourcePassword;$env:EMS_TEST_ADMIN_PASSWORD=$AdminPassword
    Write-Host "Release task: $($run.path); keep=$KeepArtifacts"
    $nativeCode=Invoke-ArtifactNative $MavenExe @('-B','-f',"$root\backend\pom.xml",'test') (Join-Path $run.path 'logs/maven.log')
    if($nativeCode -ne 0){throw 'Maven tests failed'}
    foreach($file in (Get-ChildItem -LiteralPath "$root\backend\target\surefire-reports" -Filter '*.txt')){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $run.path 'logs')}
    Write-Host 'Unit/MVC checks passed. Building isolated candidate...'
    $nativeCode=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\build-release-worker.ps1",'-SourceMySqlHome',$SourceMySqlHome,'-VisualCppRedist',$VisualCppRedist,'-VisualCppLicense',$VisualCppLicense,'-SourceHost',$SourceHost,'-SourcePort',[string]$SourcePort,'-SourceUser',$SourceUser,'-MavenExe',$MavenExe,'-ContextPath',(Join-Path $run.path 'run.json')) (Join-Path $run.path 'logs/build.log')
    if($nativeCode -ne 0){throw 'Candidate build/restore verification failed'}
    $candidate=Join-Path $run.path 'candidate.zip'
    & "$PSScriptRoot\verify-package.ps1" -ZipPath $candidate -ProjectRoot $root -OutputFile (Join-Path $run.path 'candidate-results.txt')
    Write-Host 'Candidate passed. Running HTTP, browser, Excel and development compatibility checks...'
    $nativeCode=Invoke-ArtifactNative $shell @('-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\test-portable.ps1",'-ZipPath',$candidate,'-ContextPath',(Join-Path $run.path 'run.json'),'-JavaExe',$JavaExe,'-MavenExe',$MavenExe,'-NodeExe',$NodeExe,'-BrowserExe',$BrowserExe,'-OpenBrowser') (Join-Path $run.path 'logs/acceptance.log')
    if($nativeCode -ne 0){throw 'Integrated candidate verification failed'}
    # Shutdown checks precede final publication; they never discard caller-owned data.
    Stop-ArtifactInstances $run
    $version=Get-ArtifactVersion $root
    $lines=New-Object Collections.Generic.List[string]
    $lines.Add("# EMS v$version 验收记录")
    $lines.Add("日期："+(Get-Date -Format 'yyyy-MM-dd'))
    $lines.Add('Maven单元/MVC、HTTP三角色业务、Chrome浏览器、Excel数值、IDEA等效主类与便携实例隔离检查均通过。')
    foreach($name in @('acceptance-results.txt','browser-results.txt','compatibility-results.txt','numeric-results.txt')){
        $lines.Add("");$lines.Add("## $name")
        $lines.Add([IO.File]::ReadAllText((Join-Path $run.path $name)))
    }
    $lines.Add('');$lines.Add('未使用全新Windows虚拟机或物理断网环境；不将PATH隔离验证等同于干净系统验证。')
    $lines.Add('完整工程最终体积、历史清理与连续三轮结果见空间审计报告；具体任务和收尾状态见.release-evidence。')
    $report=Join-Path $root "验收记录_v$version.md"
    [IO.File]::WriteAllText($report,($lines -join [Environment]::NewLine),(New-Object Text.UTF8Encoding($false)))
    $stage=Join-Path $run.path "build/EMS_v$version"
    Copy-Item -LiteralPath $report -Destination $stage -Force
    $final=Join-Path $run.path 'final.zip'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $out=[IO.Compression.ZipFile]::Open($final,[IO.Compression.ZipArchiveMode]::Create)
    try{foreach($file in (Get-ArtifactFiles $stage -Strict)){
        $entry="EMS_v$version/"+$file.FullName.Substring($stage.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($out,$file.FullName,$entry,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
    }}finally{$out.Dispose()}
    & "$PSScriptRoot\verify-package.ps1" -ZipPath $final -ProjectRoot $root -OutputFile (Join-Path $run.path 'package-results.txt')
    $hash=(Get-FileHash -LiteralPath $final -Algorithm SHA256).Hash
    $destination=Join-Path $root "dist/EMS员工管理系统_v$($version)_Windows_x64.zip"
    $sum=Join-Path $run.path 'final.sha256'
    [IO.File]::WriteAllText($sum,($hash+'  '+[IO.Path]::GetFileName($destination)),(New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $final -Destination $destination -Force
    Move-Item -LiteralPath $sum -Destination ($destination+'.sha256') -Force
    Write-ArtifactJson (Join-Path $run.path 'publication-results.json') @{status='published';version=$version;sha256=$hash;file=[IO.Path]::GetFileName($destination)}
    $success=$true;Write-Host "Verified release published: $destination"
}catch{$errorText=$_.Exception.Message;Write-Warning $errorText}finally{
    foreach($action in @(
        {if(Test-Path -LiteralPath "$root\backend\target\classes"){Copy-Item -LiteralPath "$root\backend\src\main\resources\application.yml" -Destination "$root\backend\target\classes\application.yml" -Force}},
        {$env:EMS_SOURCE_DB_PASSWORD=$oldSource;$env:EMS_TEST_ADMIN_PASSWORD=$oldAdmin}
    )){try{& $action}catch{$errorText+='; restore: '+$_.Exception.Message;$success=$false}}
}
$code=Complete-ArtifactRun $run $success $errorText
exit $code
