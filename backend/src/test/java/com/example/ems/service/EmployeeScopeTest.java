package com.example.ems.service;

import com.example.ems.model.SessionUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmployeeScopeTest {
    private String queryFor(String scope, int role, Long departmentId) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        EmployeeService service = new EmployeeService(jdbc, mock(LogService.class));
        service.page(new SessionUser(11L, "000011", "Tester", role, departmentId, "Department"),
                null, null, null, scope, 1, 20);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    @Test
    void staffCanSeeActiveOtherDepartmentsWithoutPrivateColumns() {
        String sql = queryFor("other", 0, 4L);
        assertTrue(sql.contains("e.department_id<>? OR e.department_id IS NULL"));
        assertTrue(sql.contains("e.employment_status=1"));
        assertFalse(sql.contains("e.phone"));
        assertFalse(sql.contains("e.birth_date"));
        assertFalse(sql.contains("basic_salary"));
    }

    @Test
    void managerAllScopeIncludesOwnAndActiveOthers() {
        String sql = queryFor("all", 1, 4L);
        assertTrue(sql.contains("e.department_id=? OR e.employment_status=1"));
    }

    @Test
    void executiveAllScopeKeepsSalaryAndNoDepartmentRestriction() {
        String sql = queryFor("all", 2, null);
        assertTrue(sql.contains("basic_salary"));
        assertFalse(sql.contains("e.department_id=?"));
    }

    @Test
    void selectedDepartmentsUseInClauseAndKeepCrossDepartmentPrivacy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        EmployeeService service = new EmployeeService(jdbc, mock(LogService.class));
        service.page(new SessionUser(11L, "000011", "Tester", 0, 4L, "Department"),
                null, null, List.of(3L, 5L), false, 1, 20);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("e.department_id IN (?,?)"));
        assertTrue(sql.getValue().contains("e.department_id=? OR e.employment_status=1"));
        assertFalse(sql.getValue().contains("basic_salary"));
    }

    @Test
    void noDepartmentSelectionReturnsNoRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        new EmployeeService(jdbc, mock(LogService.class))
                .page(new SessionUser(11L, "000011", "Tester", 2, null, "Department"),
                        null, null, List.of(), false, 1, 20);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("AND 1=0"));
    }

    @Test
    void staffAndManagerDefaultToTheirOwnDepartmentWhileExecutiveDefaultsToAll() {
        for (int role : new int[]{0, 1, 2}) {
            EmployeeService service = mock(EmployeeService.class);
            DepartmentService departments = mock(DepartmentService.class);
            com.example.ems.service.ProfileService profiles = mock(com.example.ems.service.ProfileService.class);
            jakarta.servlet.http.HttpSession session = mock(jakarta.servlet.http.HttpSession.class);
            org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();
            when(session.getAttribute("user")).thenReturn(new SessionUser(11L, "000011", "Tester", role, 4L, "My Department"));
            when(departments.listDepartments()).thenReturn(List.of(
                    java.util.Map.of("id", 4L, "department_name", "My Department"),
                    java.util.Map.of("id", 5L, "department_name", "Other Department")));
            when(service.page(any(), isNull(), isNull(), anyList(), anyBoolean(), eq(1), eq(20)))
                    .thenReturn(new EmployeeService.Page(List.of(), 0, 1, 20, 1));
            if (role == 1) when(departments.positionsByDepartment(4L)).thenReturn(List.of());
            new com.example.ems.web.EmployeeController(service, departments, profiles)
                    .list(null, null, null, null, null, false, 1, 20, session, model);
            @SuppressWarnings("unchecked")
            List<Long> selected = (List<Long>) model.getAttribute("selectedDepartmentIds");
            assertEquals(role == 2 ? List.of(4L, 5L) : List.of(4L), selected);
            assertEquals(role == 2, model.getAttribute("includeUnassigned"));
            assertEquals(4L, model.getAttribute("myDepartmentId"));
        }
    }

    @Test
    void invalidScopeIsRejected() {
        EmployeeService service = new EmployeeService(mock(JdbcTemplate.class), mock(LogService.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.page(new SessionUser(11L, "000011", "Tester", 0, 4L, "Department"),
                        null, null, null, "unexpected", 1, 20));
    }
}
