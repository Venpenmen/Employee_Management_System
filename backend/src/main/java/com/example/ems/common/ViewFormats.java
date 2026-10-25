package com.example.ems.common;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 模板统一展示格式（模板中以 {@code @viewFormats} 引用）。
 * 只影响页面显示，不修改数据库数值、工资计算或 Excel 导出。
 */
@Component("viewFormats")
public class ViewFormats {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Clock clock;

    public ViewFormats() { this(Clock.system(ZONE)); }
    public ViewFormats(Clock clock) { this.clock = clock; }

    /** 日期：yyyy-MM-dd；空值返回 null，便于模板使用 ?: 指定占位文字。 */
    public String date(Object value) {
        LocalDate date = toDate(value);
        return date == null ? null : DATE.format(date);
    }

    /** 日期时间：yyyy-MM-dd HH:mm；空值返回 null。 */
    public String dateTime(Object value) {
        LocalDateTime dateTime = toDateTime(value);
        if (dateTime != null) return DATE_TIME.format(dateTime);
        LocalDate date = toDate(value);
        return date == null ? null : DATE.format(date);
    }

    /** 时间：HH:mm；空值返回 null。 */
    public String time(Object value) {
        if (value == null) return null;
        if (value instanceof LocalTime t) return TIME.format(t);
        if (value instanceof java.sql.Time t) return TIME.format(t.toLocalTime());
        LocalDateTime dateTime = toDateTime(value);
        if (dateTime != null) return TIME.format(dateTime);
        if (value instanceof CharSequence text) {
            try { return TIME.format(LocalTime.parse(text.toString().trim())); } catch (RuntimeException ignored) { return text.toString(); }
        }
        return value.toString();
    }

    /** 人民币金额：¥1,234.50；空值显示“—”。仅为展示，计算仍使用数据库 Decimal。 */
    public String money(Object value) {
        BigDecimal amount = toDecimal(value);
        if (amount == null) return "—";
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.CHINA));
        format.setRoundingMode(RoundingMode.HALF_UP);
        String text = format.format(amount.abs());
        return (amount.signum() < 0 ? "-¥" : "¥") + text;
    }

    /** 按上海时间问候。 */
    public String greeting() {
        int hour = LocalTime.now(clock.withZone(ZONE)).getHour();
        if (hour < 5) return "晚上好";
        if (hour < 11) return "早上好";
        if (hour < 13) return "中午好";
        if (hour < 18) return "下午好";
        return "晚上好";
    }

    private LocalDate toDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof LocalDateTime dt) return dt.toLocalDate();
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (value instanceof java.util.Date d) return d.toInstant().atZone(ZONE).toLocalDate();
        if (value instanceof CharSequence text) {
            String s = text.toString().trim();
            if (s.isEmpty()) return null;
            try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    private LocalDateTime toDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dt) return dt;
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (value instanceof OffsetDateTime odt) return odt.atZoneSameInstant(ZONE).toLocalDateTime();
        if (value instanceof ZonedDateTime zdt) return zdt.withZoneSameInstant(ZONE).toLocalDateTime();
        if (value instanceof Instant instant) return LocalDateTime.ofInstant(instant, ZONE);
        if (value instanceof java.sql.Date || value instanceof LocalDate) return null;
        if (value instanceof java.util.Date d) return LocalDateTime.ofInstant(d.toInstant(), ZONE);
        if (value instanceof CharSequence text) {
            String s = text.toString().trim();
            if (s.length() < 16) return null;
            try { return LocalDateTime.parse(s.replace(' ', 'T')); } catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal b) return b;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(value.toString().trim()); } catch (RuntimeException ignored) { return null; }
    }
}
