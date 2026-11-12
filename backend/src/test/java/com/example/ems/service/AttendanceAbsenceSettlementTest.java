package com.example.ems.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceAbsenceSettlementTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AttendanceCalendar calendar = mock(AttendanceCalendar.class);
    private final AttendanceAbsenceSettlement settlement = new AttendanceAbsenceSettlement(jdbc, calendar);
    private final AttendanceRule rule = new AttendanceRule(9L, LocalTime.of(9,0), LocalTime.of(18,0),
            5,5,30,30, LocalTime.of(8,0), LocalTime.of(20,0));

    private void setup() {
        when(calendar.workflowStart()).thenReturn(DAY.minusDays(2));
        when(calendar.workday(DAY)).thenReturn(true);
        when(calendar.rule(DAY)).thenReturn(rule);
    }

    @Test void boundaryExactlyAtLatestCheckoutDoesNotSettle() {
        setup();
        assertEquals(0, settlement.settleDate(DAY, DAY.atTime(20,0)));
        verify(jdbc, never()).queryForList(contains("FROM employee"), eq(Long.class), any(Object[].class));
    }

    @Test void settlesOnlyReturnedEligibleEmployeesAfterLatestCheckout() {
        setup();
        when(jdbc.queryForList(contains("FROM employee"), eq(Long.class),
                any(Object[].class))).thenReturn(List.of(7L, 8L));
        when(jdbc.update(contains("INSERT IGNORE INTO attendance_record"), any(Object[].class))).thenReturn(1);
        assertEquals(2, settlement.settleDate(DAY, DAY.atTime(20,0,1)));
        verify(jdbc, times(2)).update(contains("INSERT IGNORE INTO attendance_record"), any(Object[].class));
    }

    @Test void doesNotSettleNonWorkdayOrBeforeWorkflowStart() {
        setup();
        when(calendar.workday(DAY)).thenReturn(false);
        assertEquals(0, settlement.settleDate(DAY, DAY.atTime(20,1)));
        assertEquals(0, settlement.settleDate(DAY.minusDays(3), DAY.atTime(21,0)));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void pastDayCanBeCaughtUpWithoutChangingExistingRecords() {
        setup();
        when(jdbc.queryForList(contains("FROM employee"), eq(Long.class),
                any(Object[].class))).thenReturn(List.of());
        assertEquals(0, settlement.settleDate(DAY, DAY.plusDays(1).atTime(9,0)));
        verify(jdbc, never()).update(contains("INSERT IGNORE INTO attendance_record"), any(Object[].class));
    }
}
