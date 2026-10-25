# Sourced by test-portable.ps1; all writes target its disposable fixture database.
function Invoke-ExpectedFormError($Uri,$Session,$Body,[int]$Status) {
    # PowerShell 5 strips HTML input elements from ErrorDetails; inspect the actual response body.
    $request=[Net.HttpWebRequest]::Create($Uri)
    $request.Method='POST';$request.CookieContainer=$Session.Cookies
    $request.ContentType='application/x-www-form-urlencoded; charset=UTF-8'
    $encoded=($Body.GetEnumerator()|ForEach-Object{[uri]::EscapeDataString([string]$_.Key)+'='+[uri]::EscapeDataString([string]$_.Value)}) -join '&'
    $bytes=[Text.Encoding]::UTF8.GetBytes($encoded);$request.ContentLength=$bytes.Length
    $stream=$request.GetRequestStream();try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
    try {$response=$request.GetResponse()}catch [Net.WebException] {$response=$_.Exception.Response;if(!$response){throw}}
    try {
        if([int]$response.StatusCode -ne $Status){throw "Expected HTTP $Status from $Uri, received $([int]$response.StatusCode)"}
        $reader=New-Object IO.StreamReader($response.GetResponseStream(),[Text.Encoding]::UTF8)
        try{return [pscustomobject]@{Content=$reader.ReadToEnd();StatusCode=$Status}}finally{$reader.Dispose()}
    } finally {$response.Dispose()}
}
function Get-ProfileToken($Session) {
    $page=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/edit" -WebSession $Session
    if($page.Content -match '<title>操作失败</title>'){throw 'Profile edit template failed to render'}
    $match=[regex]::Match($page.Content,'name="_csrf"[^>]*value="([^"]+)"')
    if(-not $match.Success){throw 'Profile CSRF field not rendered'}
    return $match.Groups[1].Value
}
function Invoke-ProfileAcceptance {
    $token=Get-ProfileToken $employee
    $page=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile" -WebSession $employee
    Assert-Test ($page.Content -match '教育经历' -and $page.Content -match '证书' -and $page.Content -notmatch '<title>操作失败</title>') 'Seven-section personal archive renders for an existing employee'
    $version=[long](Invoke-FixtureSql "SELECT COALESCE(MAX(p.version),0) FROM employee_profile p JOIN employee e ON e.id=p.employee_id WHERE e.employee_no='$employeeNo'")
    $body=@{_csrf=$token;version=$version;phone='13900009999';email='archive-test@example.com';address='隐私地址甲';emergencyName='隐私联系人甲';emergencyRelation='家属';emergencyPhone='13900008888';biography='档案验收简介';gender='1';employeeId='1';userType='2';departmentId='999';basicSalary='999999'}
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile" -Method Post -WebSession $employee -Body $body
    Assert-Test ((Invoke-FixtureSql "SELECT p.email FROM employee_profile p JOIN employee e ON e.id=p.employee_id WHERE e.employee_no='$employeeNo'") -eq 'archive-test@example.com') 'Basic profile saves to the logged-in owner'
    Assert-Test ((Invoke-FixtureSql "SELECT user_type FROM employee WHERE employee_no='$employeeNo'") -eq '0') 'Extra employee ID, role, department and salary fields cannot change protected data'
    $null=Invoke-ExpectedFormError "$baseUrl/profile" $employee $body 409
    Assert-Test ((Invoke-FixtureSql "SELECT version FROM employee_profile p JOIN employee e ON e.id=p.employee_id WHERE e.employee_no='$employeeNo'") -eq [string]($version+1)) 'Stale basic-profile form is rejected without overwriting the newer version'
    $body.version=$version+1
    $body.phone=Invoke-FixtureSql "SELECT phone FROM employee WHERE employee_no='$adminNo'"
    $null=Invoke-ExpectedFormError "$baseUrl/profile" $employee $body 400
    Assert-Test ((Invoke-FixtureSql "SELECT phone FROM employee WHERE employee_no='$employeeNo'") -eq '13900009999') 'Duplicate phone is rejected and the original phone is preserved'
    $body.phone='13900009999'
    $body.email='bad-email'
    $invalid=Invoke-ExpectedFormError "$baseUrl/profile" $employee $body 400
    Assert-Test ($invalid.Content -match 'bad-email' -and $invalid.Content -match '邮箱格式不正确') 'Invalid contact details return validation feedback and retain input'
    $body.email='archive-test@example.com'
    $noToken=@{version=$version+1;phone='13900009999';email='forged@example.com'}
    $blocked=$false
    try{$null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile" -Method Post -WebSession $employee -Body $noToken}catch{$blocked=[int]$_.Exception.Response.StatusCode -eq 403}
    Assert-Test $blocked 'Real HTTP profile save rejects missing CSRF token'

    # Force audit failure to prove that both business writes and the audit are one transaction.
    $null=Invoke-FixtureSql "CREATE TRIGGER ems_profile_test_audit BEFORE INSERT ON operation_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit test failure'"
    try {
        $body.email='must-rollback@example.com'
        $failed=$false
        try {$null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile" -Method Post -WebSession $employee -Body $body}
        catch {$failed=[int]$_.Exception.Response.StatusCode -eq 500}
        Assert-Test $failed 'Unexpected database failure returns HTTP 500'
    } finally {$null=Invoke-FixtureSql 'DROP TRIGGER ems_profile_test_audit'}
    Assert-Test ((Invoke-FixtureSql "SELECT email FROM employee_profile p JOIN employee e ON e.id=p.employee_id WHERE e.employee_no='$employeeNo'") -eq 'archive-test@example.com') 'Audit insertion failure rolls back basic-profile changes and version'
    $body.email='archive-test@example.com'

    $definitions=@(
        @{kind='education';table='employee_education';body=@{school='测试大学';degree='本科';major='计算机';startMonth='2018-09';endMonth='2022-06'};field='school';edited='更新大学'},
        @{kind='experience';table='employee_experience';body=@{company='测试单位';department='研发';position='开发';startMonth='2022-07';endMonth='2025-06';description='职责说明'};field='company';edited='更新单位'},
        @{kind='skills';table='employee_skill';body=@{name='Java';proficiency='熟练';notes='测试说明'};field='name';edited='SQL'},
        @{kind='certificates';table='employee_certificate';body=@{name='测试证书';issuer='测试机构';certificateNumber='TEST-1';obtainedDate='2024-01-01';expiryDate='2028-01-01'};field='name';edited='更新证书'}
    )
    $invalidEducation=@{_csrf=$token;school='保留学校';degree='本科';startMonth='2025-09';endMonth='2024-06'}
    $invalid=Invoke-ExpectedFormError "$baseUrl/profile/education" $employee $invalidEducation 400
    Assert-Test ($invalid.Content -match '保留学校' -and $invalid.Content -match '结束月份') 'Invalid experience dates preserve form values and do not write a record'
    foreach($definition in $definitions) {
        $kind=$definition.kind;$table=$definition.table;$entryBody=$definition.body
        $entryBody._csrf=$token
        $entryPage=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/$kind/new" -WebSession $employee
        Assert-Test ($entryPage.Content -notmatch '<title>操作失败</title>') "Add form renders: $kind"
        $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/$kind" -Method Post -WebSession $employee -Body $entryBody
        $id=Invoke-FixtureSql "SELECT t.id FROM $table t JOIN employee e ON e.id=t.employee_id WHERE e.employee_no='$employeeNo' ORDER BY t.id DESC LIMIT 1"
        Assert-Test ($id -match '^\d+$') "Add profile entry: $kind"
        $editPage=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/$kind/$id/edit" -WebSession $employee
        Assert-Test ($editPage.Content -notmatch '<title>操作失败</title>') "Edit form renders: $kind"
        $entryBody[$definition.field]=$definition.edited
        $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/$kind/$id" -Method Post -WebSession $employee -Body $entryBody
        $column=$definition.field
        Assert-Test ((Invoke-FixtureSql "SELECT $column FROM $table WHERE id=$id") -eq $definition.edited) "Edit profile entry: $kind"
        $listed=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile" -WebSession $employee
        Assert-Test ($listed.Content -notmatch '<title>操作失败</title>' -and $listed.Content.Contains($definition.edited)) "Saved entry displays in the archive: $kind"
        $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/$kind/$id/delete" -Method Post -WebSession $employee -Body @{_csrf=$token}
        Assert-Test ((Invoke-FixtureSql "SELECT COUNT(*) FROM $table WHERE id=$id") -eq '0') "Delete profile entry: $kind"
    }
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/skills" -Method Post -WebSession $employee -Body @{_csrf=$token;name='持久化技能';proficiency='精通';notes='重启后保留'}
    $skillId=Invoke-FixtureSql "SELECT t.id FROM employee_skill t JOIN employee e ON e.id=t.employee_id WHERE e.employee_no='$employeeNo' ORDER BY t.id DESC LIMIT 1"
    $null=Invoke-FixtureSql "UPDATE user_account a JOIN department d ON d.manager_id=a.employee_id SET a.password_hash=(SELECT h.password_hash FROM (SELECT u.password_hash FROM user_account u JOIN employee e ON e.id=u.employee_id WHERE e.employee_no='$adminNo') h) WHERE d.id=$department"
    $managerNo=Invoke-FixtureSql "SELECT e.employee_no FROM employee e JOIN department d ON d.manager_id=e.id WHERE d.id=$department"
    $manager=Test-Login $managerNo $AdminPassword
    $managerToken=Get-ProfileToken $manager
    foreach($suffix in @('edit','delete')) {
        $denied=$false
        try{
            if($suffix -eq 'edit'){$null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/skills/$skillId/edit" -WebSession $manager}
            else{$null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile/skills/$skillId/delete" -Method Post -WebSession $manager -Body @{_csrf=$managerToken}}
        }catch{$denied=[int]$_.Exception.Response.StatusCode -eq 404}
        Assert-Test $denied "Manager cannot access another person's entry: $suffix"
    }
    $denied=$false
    try {$null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees/$employeeNo" -WebSession $manager}
    catch {$denied=[int]$_.Exception.Response.StatusCode -eq 403}
    Assert-Test $denied 'Manager cannot read subordinate contact archive'
    $executivePage=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees/$employeeNo" -WebSession $admin
    Assert-Test ($executivePage.Content -match 'archive-test@example.com' -and $executivePage.Content -notmatch 'href="/profile/edit"') 'Executive can read the complete archive without another-person editing controls'
    $privacyCount=Invoke-FixtureSql "SELECT COUNT(*) FROM operation_log l JOIN employee e ON e.id=l.target_id WHERE l.target_type='EMPLOYEE_PROFILE' AND e.employee_no='$employeeNo' AND (l.summary LIKE '%archive-test@example.com%' OR l.summary LIKE '%隐私地址甲%' OR l.summary LIKE '%隐私联系人甲%' OR l.summary LIKE '%13900008888%')"
    Assert-Test ($privacyCount -eq '0') 'Profile audit summaries exclude contact values, addresses and personal text'
    $archive=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile" -WebSession $employee
    Assert-Test ($archive.Content -match '持久化技能' -and $archive.Content -match '更新基本档案') 'Archive lists saved records and safe change history'
}
function Test-ProfilePersistence {
    Assert-Test ((Invoke-FixtureSql "SELECT email FROM employee_profile p JOIN employee e ON e.id=p.employee_id WHERE e.employee_no='$employeeNo'") -eq 'archive-test@example.com') 'Personal-profile fields persist across restart and directory relocation'
    Assert-Test ((Invoke-FixtureSql "SELECT COUNT(*) FROM employee_skill s JOIN employee e ON e.id=s.employee_id WHERE e.employee_no='$employeeNo' AND s.name='持久化技能'") -eq '1') 'Profile detail records persist after repeating the idempotent schema upgrade'
}
