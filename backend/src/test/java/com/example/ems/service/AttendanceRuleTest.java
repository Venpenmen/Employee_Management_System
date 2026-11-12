package com.example.ems.service;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

import static com.example.ems.service.AttendanceRule.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class AttendanceRuleTest {
    private final AttendanceRule rule = new AttendanceRule(1, time("09:00"), time("17:00"),
            5, 5, 30, 30, time("08:00"), time("20:00"));

    @Test void checkInBoundaries() {
        assertEquals(CLOSED, rule.checkIn(time("07:59:59")));
        assertEquals(NORMAL, rule.checkIn(time("08:00")));
        assertEquals(NORMAL, rule.checkIn(time("09:00")));
        assertEquals(WARNING, rule.checkIn(time("09:00:01")));
        assertEquals(WARNING, rule.checkIn(time("09:05")));
        assertEquals(EXCEPTION, rule.checkIn(time("09:05:01")));
        assertEquals(EXCEPTION, rule.checkIn(time("09:35")));
        assertEquals(CLOSED, rule.checkIn(time("09:35:01")));
    }

    @Test void checkOutBoundaries() {
        assertEquals(CLOSED, rule.checkOut(time("16:24:59")));
        assertEquals(EXCEPTION, rule.checkOut(time("16:25")));
        assertEquals(EXCEPTION, rule.checkOut(time("16:54:59")));
        assertEquals(WARNING, rule.checkOut(time("16:55")));
        assertEquals(WARNING, rule.checkOut(time("16:59:59")));
        assertEquals(NORMAL, rule.checkOut(time("17:00")));
        assertEquals(NORMAL, rule.checkOut(time("20:00")));
        assertEquals(CLOSED, rule.checkOut(time("20:00:01")));
    }

    @Test void roundsDeviationAndHandlesZeroRanges() {
        assertEquals(1, rule.deviation(time("09:00:01"), true));
        assertEquals(6, rule.deviation(time("16:54:59"), false));
        var exact = new AttendanceRule(1, rule.start(), rule.end(), 0, 0, 0, 0,
                rule.earliestIn(), rule.latestOut());
        exact.validate();
        assertEquals(CLOSED, exact.checkIn(time("09:00:01")));
        assertEquals(CLOSED, exact.checkOut(time("16:59:59")));
        assertThrows(IllegalArgumentException.class, () -> new AttendanceRule(1,
                rule.start(), rule.end(), 0, 0, 480, 0, rule.earliestIn(), rule.latestOut()).validate());
    }

    private static LocalTime time(String text) {
        return LocalTime.parse(text);
    }
}
