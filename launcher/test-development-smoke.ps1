param(
    [Parameter(Mandatory=$true)][string]$DatabaseUrl,
    [Parameter(Mandatory=$true)][string]$DatabaseUser,
    [Parameter(Mandatory=$true)][string]$DatabasePassword,
    [Parameter(Mandatory=$true)][int]$HttpPort,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$JavaExe='java.exe'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if($DatabaseUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:(\d+)/' -or $Matches[1] -eq '3306') {throw 'Use an explicit, independent loopback MySQL test instance on a non-default port.'}
$probe=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$HttpPort)
try{$probe.Start()}finally{$probe.Stop()}
[IO.Directory]::CreateDirectory($OutputDirectory)|Out-Null
$old=@{};foreach($name in @('EMS_DB_URL','EMS_DB_USER','EMS_DB_PASSWORD','EMS_BROWSER_AUTO_OPEN')){$old[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$checks=New-Object Collections.Generic.List[string]
function Check($Condition,$Message){if(!$Condition){throw $Message};$checks.Add($Message);Write-Host "PASS: $Message"}
function Test-SyntheticImport($Session) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.Net.Http
    $suffix=([Convert]::ToInt64([guid]::NewGuid().ToString('N').Substring(0,8),16)%1000000).ToString('000000')
    $rows=@(@('Name','Identity','Birth','Hire','Department','Position'),@(('QA_IMPORT_'+$suffix),('110101199901'+$suffix),'1999-01-01','2026-01-01','',''),@('QA_INVALID','bad-identity','1999-01-01','2026-01-01','',''))
    $xml='<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
    $rowNo=0;foreach($row in $rows){$rowNo++;$xml+='<row r="'+$rowNo+'">';for($i=0;$i -lt 6;$i++){$ref=[string][char](65+$i)+$rowNo;$xml+='<c r="'+$ref+'" t="inlineStr"><is><t>'+[Security.SecurityElement]::Escape([string]$row[$i])+'</t></is></c>'};$xml+='</row>'};$xml+='</sheetData></worksheet>'
    $parts=@{
        '[Content_Types].xml'='<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>'
        '_rels/.rels'='<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'
        'xl/workbook.xml'='<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Synthetic" sheetId="1" r:id="rId1"/></sheets></workbook>'
        'xl/_rels/workbook.xml.rels'='<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'
        'xl/worksheets/sheet1.xml'=$xml
    }
    $memory=New-Object IO.MemoryStream
    $zip=New-Object IO.Compression.ZipArchive($memory,[IO.Compression.ZipArchiveMode]::Create,$true)
    try{foreach($part in $parts.Keys){$writer=New-Object IO.StreamWriter($zip.CreateEntry($part).Open(),(New-Object Text.UTF8Encoding($false)));try{$writer.Write($parts[$part])}finally{$writer.Dispose()}}}finally{$zip.Dispose()}
    $bytes=$memory.ToArray();$memory.Dispose()
    $handler=New-Object Net.Http.HttpClientHandler;$handler.CookieContainer=$Session.Cookies
    $client=New-Object Net.Http.HttpClient($handler)
    try{foreach($attempt in @(1,2)){
        $form=New-Object Net.Http.MultipartFormDataContent
        $file=New-Object Net.Http.ByteArrayContent(,$bytes);$form.Add($file,'file','synthetic.xlsx')
        try{$response=$client.PostAsync($base+'/admin/recruitment/import',$form).GetAwaiter().GetResult();$html=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $expected=if($attempt -eq 1){1}else{0}
            if(!$response.IsSuccessStatusCode -or $html -notmatch ("<b>$expected</b>\s*<span>成功")){[IO.File]::WriteAllText((Join-Path $OutputDirectory 'import-failure.html'),$html,(New-Object Text.UTF8Encoding($false)))}
            Check ($response.IsSuccessStatusCode -and $html -match ("<b>$expected</b>\s*<span>成功")) "Synthetic Excel import attempt $attempt has $expected successful row(s), with invalid/duplicate rows rejected"
        }finally{if($response){$response.Dispose()};$form.Dispose()}
    }}finally{$client.Dispose();$handler.Dispose()}
}
$app=$null
try {
    $env:EMS_DB_URL=$DatabaseUrl;$env:EMS_DB_USER=$DatabaseUser;$env:EMS_DB_PASSWORD=$DatabasePassword;$env:EMS_BROWSER_AUTO_OPEN='false'
    $app=Start-Process -FilePath $JavaExe -ArgumentList @('-jar',('"'+(Join-Path $root 'backend/target/employee-management-system-1.1.3.jar')+'"'),'--server.address=127.0.0.1',"--server.port=$HttpPort") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutputDirectory 'startup.log') -RedirectStandardError (Join-Path $OutputDirectory 'startup-error.log')
    $started=$app.StartTime;$base="http://127.0.0.1:$HttpPort";$ready=$false
    for($i=0;$i -lt 60;$i++){
        $app.Refresh();if($app.HasExited){throw 'Development application exited before readiness'}
        try{$r=Invoke-WebRequest -UseBasicParsing -Uri "$base/login" -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{}
        Start-Sleep -Milliseconds 500
    }
    Check $ready 'Development JAR starts with external credentials against the isolated database'
    foreach($role in @(@{number='260001';password='Admin123';routes=@('/profile','/employees','/admin/departments','/admin/positions/manage','/admin/recruitment','/admin/salaries','/attendance/manage','/leaves/approvals','/admin/logs')},@{number='260002';password='Manager123';routes=@('/profile','/employees','/attendance/manage','/leaves/approvals')},@{number='260005';password='Employee123';routes=@('/profile','/employees','/attendance/my','/leaves/my','/my-salaries')})){
        $session=New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $null=Invoke-WebRequest -UseBasicParsing -Uri "$base/login" -Method Post -WebSession $session -Body @{employeeNo=$role.number;password=$role.password}
        Check ((Invoke-RestMethod -Uri "$base/api/me" -WebSession $session).data.employeeNo -eq $role.number) "Login and authenticated API: $($role.number)"
        foreach($route in $role.routes){$page=Invoke-WebRequest -UseBasicParsing -Uri ($base+$route) -WebSession $session;Check ($page.StatusCode -eq 200 -and $page.Content -notmatch 'SpelEvaluationException|TemplateInputException') "Rendered $($role.number): $route"}
        if($role.number -eq '260001'){Test-SyntheticImport $session}
        $null=Invoke-WebRequest -UseBasicParsing -Uri "$base/logout" -Method Post -WebSession $session
        $denied=$false;try{$null=Invoke-WebRequest -UseBasicParsing -Uri "$base/api/me" -WebSession $session}catch{$denied=[int]$_.Exception.Response.StatusCode -eq 401}
        Check $denied "Logout invalidates API session: $($role.number)"
    }
    foreach($route in @('/css/app.css','/js/profile-forms.js')){Check ((Invoke-WebRequest -UseBasicParsing -Uri ($base+$route)).StatusCode -eq 200) "Static resource: $route"}
} finally {
    if($app){$app.Refresh();if(!$app.HasExited -and $app.StartTime -eq $started){$app.Kill();$null=$app.WaitForExit(10000)}}
    foreach($name in $old.Keys){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')}
}
$probe=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$HttpPort)
try{$probe.Start();Check $true 'Smoke-test HTTP port is released after the owned process exits'}finally{$probe.Stop()}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'results.txt'),($checks -join [Environment]::NewLine),(New-Object Text.UTF8Encoding($false)))
