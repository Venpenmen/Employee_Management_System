# Sourced by test-portable.ps1; fixture-only data.
function Invoke-V112Acceptance {
    foreach($roleSession in @($employee,$admin)) {
        $before=$roleSession.Cookies.GetCookies([uri]$baseUrl)['JSESSIONID'].Value
        $number=if($roleSession -eq $admin){$adminNo}else{$employeeNo}
        $password=if($roleSession -eq $admin){$AdminPassword}else{'Fixture123'}
        $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/login" -Method Post -WebSession $roleSession -Body @{employeeNo=$number;password=$password}
        $after=$roleSession.Cookies.GetCookies([uri]$baseUrl)['JSESSIONID'].Value
        Assert-Test ($before -and $after -and $before -ne $after) 'Real HTTP successful login rotates Session ID'
    }
    $otherDepartment=Invoke-FixtureSql "SELECT id FROM department WHERE id<>$department AND status=1 ORDER BY id LIMIT 1"
    foreach($i in 1..50) {
        $dept=if($i -le 25){$department}else{$otherDepartment}
        $no=980000+$i
        $identity='1101011999019'+$i.ToString('00000')
        $null=Invoke-FixtureSql "INSERT INTO employee(employee_no,name,identity_number,birth_date,department_id,user_type,hire_date,employment_status) VALUES('$no','分页验收$i','$identity','1999-01-01',$dept,0,'2026-01-01',1)"
    }
    $managerNo=Invoke-FixtureSql "SELECT e.employee_no FROM employee e JOIN department d ON d.manager_id=e.id WHERE d.id=$department"
    $manager=Test-Login $managerNo $AdminPassword
    foreach($roleSession in @($employee,$manager,$admin)){
        $executive=$roleSession -eq $admin
        $expected=if($executive){50}else{25}
        $last=if($executive){3}else{2}
        $page=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees?keyword=$([uri]::EscapeDataString('分页验收'))&departmentId=$otherDepartment&page=999&status=1" -WebSession $roleSession
        # Executive department filter legitimately narrows the result to the other department.
        if($executive){$expected=25;$last=2}
        Assert-Test ($page.Content -match "共 $expected 条" -and $page.Content -match "第 $last / $last 页") 'Paging count and last-page clamp respect role and department filter'
        if(-not $executive){Assert-Test ($page.Content -notmatch '>980026<') 'Forged department filter cannot expose another department'}
        $empty=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees?keyword=no-such-v112-employee&page=999" -WebSession $roleSession
        Assert-Test ($empty.Content -match '共 0 条' -and $empty.Content -match '第 1 / 1 页' -and $empty.Content -match '没有符合条件') 'Empty queries show page one and readable no-result feedback'
        foreach($size in @(20,50,100,7)){
            $page=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees?keyword=$([uri]::EscapeDataString('分页验收'))&page=0&pageSize=$size" -WebSession $roleSession
            $normalized=if($size -eq 7){20}else{$size}
            Assert-Test ($page.Content -match '第 1 /' -and $page.Content -match ('value="'+$normalized+'" selected="selected"')) 'Nonpositive page and supported/unsupported page capacity normalize correctly'
        }
    }
    $page=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees?keyword=$([uri]::EscapeDataString('分页验收'))&departmentId=$department&status=1&page=2&pageSize=20" -WebSession $admin
    Assert-Test ($page.Content -match '共 25 条' -and $page.Content -match ('value="'+$department+'" selected="selected"') -and $page.Content -match 'value="1" selected="selected"' -and $page.Content -match 'href="/employees">重置') 'Keyword, department and status selections persist, with reset link'
    foreach($query in @('page=abc','pageSize=abc','departmentId=abc')){
        $bad=$false
        try{$null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees?$query" -WebSession $admin}catch{$bad=[int]$_.Exception.Response.StatusCode -eq 400}
        Assert-Test $bad 'Nonnumeric paging/filter parameter returns HTTP 400'
    }
    foreach($roleSession in @($employee,$manager,$admin)){
        $options=(Invoke-RestMethod -Uri "$baseUrl/api/dictionaries/employee-options" -WebSession $roleSession).data
        $positions=(Invoke-RestMethod -Uri "$baseUrl/api/departments/$department/positions" -WebSession $roleSession).data
        $serialized=($options|ConvertTo-Json -Depth 10)+($positions|ConvertTo-Json -Depth 10)
        $page=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/dashboard" -WebSession $roleSession
        if($roleSession -eq $admin){Assert-Test ($serialized -match 'base_salary' -and $page.Content -match '基础工资') 'Executive keeps management salary fields'}
        else {
            Assert-Test ($serialized -notmatch 'base_salary|position_salary|basic_salary' -and $page.Content -notmatch '基础工资') 'Employee and manager responses exclude department/position salary fields'
            $denied=$false
            try{$null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/departments" -WebSession $roleSession}catch{$denied=[int]$_.Exception.Response.StatusCode -eq 403}
            Assert-Test $denied 'Unauthorized management page returns HTTP 403'
        }
        Assert-Test ($page.Content -match '个人档案' -and $page.Content -match '我的考勤' -and $page.Content -match '我的工资条' -and $page.Content -match '(早上好|下午好|晚上好)') 'Dashboard greeting and personal shortcuts render'
        $profilePage=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/profile" -WebSession $roleSession
        Assert-Test ($profilePage.Content -match '基本工资标准') 'Own profile still includes personal salary standard'
    }
    foreach($route in @('/api/no-such-v112-route','/no-such-v112-page')){
        $notFound=$false
        try{$null=Invoke-WebRequest -UseBasicParsing -Uri ($baseUrl+$route) -WebSession $employee}catch{$notFound=[int]$_.Exception.Response.StatusCode -eq 404}
        Assert-Test $notFound 'Unknown API or page returns HTTP 404'
    }
    $anonymous=New-Object Microsoft.PowerShell.Commands.WebRequestSession
    foreach($route in @('/api/me','/api/dictionaries/employee-options')){
        $unauthorized=$false
        try{$null=Invoke-WebRequest -UseBasicParsing -Uri ($baseUrl+$route) -WebSession $anonymous}catch{$unauthorized=[int]$_.Exception.Response.StatusCode -eq 401}
        Assert-Test $unauthorized 'Anonymous API request returns HTTP 401'
    }
    $expired=Test-Login $employeeNo 'Fixture123'
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/logout" -Method Post -WebSession $expired
    $unauthorized=$false
    try{$null=Invoke-RestMethod -Uri "$baseUrl/api/me" -WebSession $expired}catch{$unauthorized=[int]$_.Exception.Response.StatusCode -eq 401}
    Assert-Test $unauthorized 'Logged-out session receives API 401'
    # Rejected registration must leave the employee pending.
    $null=Invoke-FixtureSql "UPDATE employee SET employment_status=0,identity_number='11010119990101234X' WHERE employee_no='980050'"
    foreach($suffix in @('','X','1234X','001234X','01234 ')){
        $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/register" -Method Post -Body @{employeeNo='980050';name='分页验收50';identitySuffix=$suffix;phone='13900007777';password='Fixture123';confirmPassword='Fixture123'}
        Assert-Test ((Invoke-FixtureSql "SELECT employment_status FROM employee WHERE employee_no='980050'") -eq '0') 'Registration rejects empty, short, long or malformed identity suffix'
    }
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/register" -Method Post -Body @{employeeNo=' 980050 ';name=' 分页验收50 ';identitySuffix='01234x';phone=' 13900007777 ';password=' Fixture123 ';confirmPassword=' Fixture123 '}
    $null=Test-Login '980050' ' Fixture123 '
    Assert-Test ((Invoke-FixtureSql "SELECT phone FROM employee WHERE employee_no='980050'") -eq '13900007777') 'Registration trims editable identity fields, accepts lowercase X, and preserves password spaces'
    $context=@{returnKeyword='分页验收';returnDepartmentId=$department;returnStatus=1;returnPage=2;returnPageSize=20;returnUrl='https://evil.invalid';reason='v112 fixture'}
    $context.positionId=$position
    $returned=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees/980001/position" -Method Post -WebSession $manager -Body $context
    Assert-Test ($returned.BaseResponse.ResponseUri.AbsolutePath -eq '/employees' -and $returned.BaseResponse.ResponseUri.Query -match 'page=2' -and $returned.Content -match 'value="分页验收"') 'Position update returns to validated original filters and page'
    $context.Remove('positionId');$context.departmentId=$otherDepartment
    $returned=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees/980001/department" -Method Post -WebSession $admin -Body $context
    Assert-Test ($returned.BaseResponse.ResponseUri.Query -match 'page=2' -and (Invoke-FixtureSql "SELECT department_id FROM employee WHERE employee_no='980001'") -eq $otherDepartment) 'Department transfer preserves the originating query'
    $context.Remove('departmentId')
    $returned=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/employees/980002/terminate" -Method Post -WebSession $admin -Body $context
    Assert-Test ($returned.BaseResponse.ResponseUri.Query -match 'page=2' -and (Invoke-FixtureSql "SELECT employment_status FROM employee WHERE employee_no='980002'") -eq '3') 'Termination preserves the originating query'
}
