package com.example.ems.service;

import com.example.ems.model.SessionUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceMissingCheckInTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 6);
    private static final SessionUser STAFF = new SessionUser(7L, "000007", "Fixture", 0, 2L, "Test");

    private record Context(AttendanceService service, JdbcTemplate jdbc,
                           AttendanceCalendar calendar, LogService logs) {}

    private static Context context(Map<String,Object> leave, List<Map<String,Object>> records) {
        var jdbc = mock(JdbcTemplate.class);
        var calendar = mock(AttendanceCalendar.class);
        var logs = mock(LogService.class);
        when(calendar.rule(DATE)).thenReturn(new AttendanceRule(12L, LocalTime.of(9,0),
                LocalTime.of(18,0), 5, 5, 30, 30, LocalTime.of(8,0), LocalTime.of(20,0)));
        when(calendar.workday(DATE)).thenReturn(true);
        when(jdbc.queryForMap(anyString(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(leave);
        when(jdbc.queryForList(startsWith("SELECT * FROM attendance_record"), eq(7L), eq(DATE)))
                .thenReturn(records);
        var service = new AttendanceService(jdbc, logs, calendar);
        ReflectionTestUtils.setField(service,"sessions",mock(SessionService.class));
        return new Context(service,jdbc,calendar,logs);
    }

    @Test void missingCheckInRequiresExceptionAndReasonWithinExistingWindow() {
        var c = context(Map.of("excused_in",0,"excused_out",0), List.of());
        when(c.calendar.now()).thenReturn(DATE.atTime(17,20),DATE.atTime(18,10),DATE.atTime(18,10),
                DATE.atTime(20,1));
        assertEquals("CLOSED",c.service.punchOptions(STAFF).checkOut());
        var available = c.service.punchOptions(STAFF);
        assertEquals("EXCEPTION",available.checkOut());
        assertTrue(available.missingCheckIn());
        assertThrows(IllegalArgumentException.class,
                () -> c.service.punch(STAFF,false,"127.0.0.1"," "));
        verify(c.jdbc,never()).update(anyString(),any(Object[].class));
        assertEquals("CLOSED",c.service.punchOptions(STAFF).checkOut());
    }

    @Test void approvedLeaveAtShiftStartDoesNotBecomeMissingCheckIn() {
        var c = context(Map.of("excused_in",1,"excused_out",0), List.of());
        when(c.calendar.now()).thenReturn(DATE.atTime(18,10));
        var options = c.service.punchOptions(STAFF);
        assertEquals("NORMAL",options.checkOut());
        assertFalse(options.missingCheckIn());
    }

    @Test void missingCheckInSubmissionPersistsEventAndReviewRequest() {
        var c = context(Map.of("excused_in",0,"excused_out",0), List.of());
        var time = DATE.atTime(18,10);
        var completed = new HashMap<String,Object>();
        completed.put("check_in_time",null);
        completed.put("check_out_time",time);
        completed.put("late_minutes",0);
        completed.put("early_leave_minutes",0);
        when(c.jdbc.queryForList(startsWith("SELECT * FROM attendance_record"),eq(7L),eq(DATE)))
                .thenReturn(List.of(),List.of(completed));
        when(c.calendar.now()).thenReturn(time);
        when(c.jdbc.queryForObject(startsWith("SELECT id FROM attendance_record"),eq(Long.class),eq(7L),eq(DATE)))
                .thenReturn(55L);
        when(c.jdbc.queryForList(contains("SELECT r.status"),eq(String.class),eq(55L)))
                .thenReturn(List.of("PENDING"));
        assertTrue(c.service.punch(STAFF,false,"127.0.0.1","忘记签到"));
        verify(c.jdbc).update(contains("INSERT INTO attendance_exception_request"),
                eq(7L),eq("忘记签到"),eq(time),eq(55L),eq("OUT"));
        verify(c.jdbc).update(startsWith("UPDATE attendance_record SET attendance_status="),eq(5),eq(55L));
    }

    @Test void signOutWithoutSignInRemainsMissingAfterApproval() {
        Map<String,Object> row = new HashMap<>();
        row.put("check_in_time",null);
        row.put("check_out_time",DATE.atTime(18,5));
        row.put("late_minutes",0);
        row.put("early_leave_minutes",0);
        var c = context(Map.of("excused_in",0,"excused_out",0),List.of(row));
        when(c.jdbc.queryForList(contains("SELECT r.status"), eq(String.class), eq(55L)))
                .thenReturn(List.of("APPROVED"));
        ReflectionTestUtils.invokeMethod(c.service,"compose",55L,7L,DATE);
        verify(c.jdbc).update(startsWith("UPDATE attendance_record SET attendance_status="),eq(5),eq(55L));
    }
}
