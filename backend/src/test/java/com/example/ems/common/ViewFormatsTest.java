package com.example.ems.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class ViewFormatsTest {
    private static ViewFormats at(int hour) {
        return new ViewFormats(Clock.fixed(LocalDateTime.of(2026, 10, 19, hour, 30).atZone(ViewFormats.ZONE).toInstant(), ViewFormats.ZONE));
    }

    @Test
    void formatsDatesTimesAndNulls() {
        ViewFormats f = new ViewFormats();
        assertEquals("2026-10-19", f.date(LocalDate.of(2026, 10, 19)));
        assertEquals("2026-10-19", f.date(java.sql.Date.valueOf("2026-10-19")));
        assertEquals("2026-10-19 08:05", f.dateTime(LocalDateTime.of(2026, 10, 19, 8, 5, 59)));
        assertEquals("2026-10-19 08:05", f.dateTime(Timestamp.valueOf("2026-10-19 08:05:00")));
        assertEquals("09:00", f.time(java.sql.Time.valueOf("09:00:00")));
        assertEquals("18:30", f.time(LocalTime.of(18, 30)));
        assertNull(f.date(null));
        assertNull(f.dateTime(null));
        assertNull(f.time(null));
    }

    @Test
    void formatsMoneyWithoutChangingValue() {
        ViewFormats f = new ViewFormats();
        assertEquals("¥1,234.50", f.money(new BigDecimal("1234.5")));
        assertEquals("¥0.00", f.money(BigDecimal.ZERO));
        assertEquals("-¥10.01", f.money(new BigDecimal("-10.005")));
        assertEquals("¥6,200.00", f.money(6200));
        assertEquals("—", f.money(null));
    }

    @Test
    void greetsByShanghaiTime() {
        assertEquals("早上好", at(8).greeting());
        assertEquals("中午好", at(12).greeting());
        assertEquals("下午好", at(15).greeting());
        assertEquals("晚上好", at(21).greeting());
        assertEquals("晚上好", at(2).greeting());
    }
}
