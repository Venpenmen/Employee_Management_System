package com.example.ems.service;

import com.example.ems.model.SessionUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeaveManagementTest {
    private record Fixture(LeaveService service, JdbcTemplate jdbc) {}
    private Fixture fixture() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(startsWith("SELECT user_type,department_id,employment_status"),any()))
            .thenReturn(Map.of("user_type",1,"department_id",7L,"employment_status",1));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*)"),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        return new Fixture(new LeaveService(jdbc,mock(LogService.class)),jdbc);
    }
    @Test void staffCannotReadManagementRecords() {
        Fixture f=fixture();
        assertThrows(ResponseStatusException.class,()->f.service.management(
            new SessionUser(8L,"000008","A",0,7L,"D"),null,"",null,null,null,1));
        verifyNoInteractions(f.jdbc);
    }
    @Test void managerAlwaysRestrictedToOwnDepartment() {
        Fixture f=fixture();
        var u=new SessionUser(8L,"000008","Manager",1,7L,"D");
        f.service.management(u,List.of(1,2),"alice",99L,
            LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),1);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.jdbc).queryForObject(sql.capture(),eq(Long.class),any(Object[].class));
        String query=sql.getValue();
        assertTrue(query.contains("e.department_id=?"));
        assertTrue(query.contains("l.status IN (?,?)"));
        assertTrue(query.contains("e.employee_no LIKE ? OR e.name LIKE ?"));
        assertTrue(query.contains("l.end_time>=?"));
        assertTrue(query.contains("l.start_time<?"));
        assertFalse(query.contains("e.department_id=99"));
    }
    @Test void noSelectedStatusesReturnsNoRecords() {
        Fixture f=fixture();
        var u=new SessionUser(8L,"000008","Manager",1,7L,"D");
        Map<String,Object> result=f.service.management(u,List.of(),"",null,null,null,1);
        assertEquals(List.of(),result.get("statuses"));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.jdbc).queryForObject(sql.capture(),eq(Long.class),any(Object[].class));
        assertTrue(sql.getValue().contains("AND 1=0"));
    }
    @Test void changedDepartmentPreventsReading() {
        Fixture f=fixture();
        var u=new SessionUser(8L,"000008","Manager",1,9L,"Other");
        assertThrows(ResponseStatusException.class,()->f.service.management(u,null,"",null,null,null,1));
    }
    @Test void decisionTimeUsesActualApprovalEventAndIgnoresWithdrawal() {
        Fixture f=fixture();
        var u=new SessionUser(8L,"000008","Manager",1,7L,"D");
        Map<String,Object> record=new LinkedHashMap<>();
        record.put("id",20L);record.put("status",2);record.put("current_approver_id",null);
        var submit=Map.<String,Object>of("leave_request_id",20L,"action","SUBMIT","action_time","2026-10-09 09:00");
        var approve=Map.<String,Object>of("leave_request_id",20L,"action","APPROVE","action_time","2026-11-11 18:28");
        var withdraw=Map.<String,Object>of("leave_request_id",20L,"action","WITHDRAW","action_time","2026-11-12 09:00");
        when(f.jdbc.queryForObject(startsWith("SELECT COUNT(*)"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        when(f.jdbc.queryForList(startsWith("SELECT l."),any(Object[].class))).thenReturn(List.of(record));
        when(f.jdbc.queryForList(startsWith("SELECT ar."),any(Object[].class))).thenReturn(List.of(submit,approve,withdraw));
        var rows=(List<?>)f.service.management(u,List.of(2),"",null,null,null,1).get("requests");
        assertEquals("2026-11-11 18:28",((Map<?,?>)rows.get(0)).get("approval_time"));
    }
    @Test void onlyAssignedPendingCaseIsActionable() {
        Fixture f=fixture();
        var u=new SessionUser(8L,"000008","Manager",1,7L,"D");
        Map<String,Object> record=new LinkedHashMap<>();
        record.put("id",20L);
        record.put("status",1);
        record.put("current_approver_id",8L);
        when(f.jdbc.queryForObject(startsWith("SELECT COUNT(*)"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        when(f.jdbc.queryForList(startsWith("SELECT l."),any(Object[].class))).thenReturn(List.of(record));
        when(f.jdbc.queryForList(startsWith("SELECT ar."),any(Object[].class))).thenReturn(List.of());
        var records=(List<?>)f.service.management(u,List.of(1),"",null,null,null,1).get("requests");
        assertEquals(true,((Map<?,?>)records.get(0)).get("can_approve"));
        record.put("status",2);
        records=(List<?>)f.service.management(u,List.of(2),"",null,null,null,1).get("requests");
        assertEquals(false,((Map<?,?>)records.get(0)).get("can_approve"));
    }
}