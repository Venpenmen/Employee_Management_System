# Sourced only by the portable harness; all SQL uses its registered disposable fixture.
function Invoke-ManagementAcceptance {
    $name='QA_DEPT_'+[guid]::NewGuid().ToString('N').Substring(0,8)
    $body=@{name=$name;baseSalary='1234.56';description='Synthetic acceptance department'}
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/departments" -Method Post -WebSession $admin -Body $body
    $id=Invoke-FixtureSql "SELECT id FROM department WHERE department_name='$name'"
    Assert-Test ($id -match '^\d+$') 'Department creation persists synthetic data'
    $null=Invoke-ExpectedFormError "$baseUrl/admin/departments" $admin $body 409
    Assert-Test ((Invoke-FixtureSql "SELECT COUNT(*) FROM department WHERE department_name='$name'") -eq '1') 'Duplicate department returns 409 without duplicate rows'
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/departments/$id/update" -Method Post -WebSession $admin -Body @{departmentName=$name;baseSalary='2345.67';description='Updated fixture'}
    Assert-Test ((Invoke-FixtureSql "SELECT base_salary FROM department WHERE id=$id") -eq '2345.67') 'Department editing persists exact money values'
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/positions" -Method Post -WebSession $admin -Body @{departmentId=$id;name='QA_POSITION';salary='500.25'}
    $job=Invoke-FixtureSql "SELECT id FROM position_info WHERE department_id=$id AND position_name='QA_POSITION'"
    Assert-Test ($job -match '^\d+$') 'Position creation is associated with the requested department'
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/positions/$job/update" -Method Post -WebSession $admin -Body @{positionName='QA_POSITION_UPDATED';positionSalary='600.75'}
    Assert-Test ((Invoke-FixtureSql "SELECT position_salary FROM position_info WHERE id=$job") -eq '600.75') 'Position editing persists exact money values'
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/positions/$job/delete" -Method Post -WebSession $admin
    Assert-Test ((Invoke-FixtureSql "SELECT COUNT(*) FROM position_info WHERE id=$job") -eq '0') 'Unused position deletion works'
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/departments/$id/delete" -Method Post -WebSession $admin
    Assert-Test ((Invoke-FixtureSql "SELECT status FROM department WHERE id=$id") -eq '2') 'Empty department dissolution preserves the row as inactive'
    $null=Invoke-ExpectedFormError "$baseUrl/admin/departments" $employee $body 403
    Assert-Test $true 'Employee cannot perform executive department writes'
    $before=Invoke-FixtureSql "SELECT COUNT(*) FROM department"
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/departments" -Method Post -WebSession $admin -Body @{name=($name+'_BAD');baseSalary='-1'}
    Assert-Test ((Invoke-FixtureSql 'SELECT COUNT(*) FROM department') -eq $before) 'Negative department salary is rejected without database writes'
    $before=Invoke-FixtureSql "SELECT COUNT(*) FROM salary_record WHERE salary_month='2098-12'"
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/admin/salaries/settle" -Method Post -WebSession $admin -Body @{month='2098-12'}
    Assert-Test ((Invoke-FixtureSql "SELECT COUNT(*) FROM salary_record WHERE salary_month='2098-12'") -eq $before) 'Repeated salary settlement does not duplicate records'
    $before=Invoke-FixtureSql "SELECT COUNT(*) FROM attendance_record a JOIN employee e ON e.id=a.employee_id WHERE e.employee_no='$employeeNo'"
    $null=Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/attendance/check-in" -Method Post -WebSession $employee
    Assert-Test ((Invoke-FixtureSql "SELECT COUNT(*) FROM attendance_record a JOIN employee e ON e.id=a.employee_id WHERE e.employee_no='$employeeNo'") -eq $before) 'Repeated check-in does not duplicate attendance records'
}
