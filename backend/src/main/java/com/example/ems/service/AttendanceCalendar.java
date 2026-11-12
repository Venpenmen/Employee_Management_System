package com.example.ems.service;

import java.time.*;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class AttendanceCalendar {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    AttendanceCalendar(JdbcTemplate jdbc, @Value("${ems.attendance.zone:Asia/Shanghai}") String zone) {
        this(jdbc, Clock.system(ZoneId.of(zone)));
    }

    AttendanceCalendar(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    LocalDate workflowStart() {
        Long installed = jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT CAST(item_label AS UNSIGNED) FROM dictionary_item
                        WHERE dict_type='__attendance_internal' AND item_value='workflow_start'),
                    (SELECT UNIX_TIMESTAMP(installed_on) FROM flyway_schema_history WHERE version='6' AND success=1))
                """, Long.class);
        return Instant.ofEpochSecond(installed).atZone(clock.getZone()).toLocalDate();
    }

    AttendanceRule rule(LocalDate date) {
        List<AttendanceRule> versions = jdbc.query("""
                SELECT * FROM attendance_rule_version WHERE effective_date<=?
                ORDER BY effective_date DESC,id DESC
                LIMIT 1
                """, (row, index) -> new AttendanceRule(row.getLong("id"),
                row.getObject("work_start", LocalTime.class), row.getObject("work_end", LocalTime.class),
                row.getInt("late_warning"), row.getInt("early_warning"), row.getInt("check_in_exception"),
                row.getInt("check_out_exception"), row.getObject("earliest_check_in", LocalTime.class),
                row.getObject("latest_check_out", LocalTime.class)), date);
        if (!versions.isEmpty()) return versions.get(0);
        return jdbc.query("""
                SELECT * FROM attendance_rule WHERE status=1 ORDER BY id DESC LIMIT 1
                """, (row, index) -> {
            LocalTime start = row.getObject("work_start_time", LocalTime.class);
            LocalTime end = row.getObject("work_end_time", LocalTime.class);
            int late = row.getInt("late_grace_minutes");
            int early = row.getInt("early_grace_minutes");
            int remaining = Math.max(0, (end.toSecondOfDay()-start.toSecondOfDay())/60-late-early-1);
            return new AttendanceRule(-row.getLong("id"), start, end, late, early, Math.min(30, remaining/2),
                    Math.min(30, remaining-remaining/2),
                    LocalTime.ofSecondOfDay(Math.max(0, start.toSecondOfDay()-3600)),
                    end.isAfter(LocalTime.of(20,0)) ? end : LocalTime.of(20,0));
        }).stream().findFirst().orElseThrow(() -> new IllegalStateException("尚未配置启用的考勤规则"));
    }

    boolean workday(LocalDate date) {
        List<Integer> overrides = jdbc.queryForList(
                "SELECT workday FROM attendance_calendar_exception WHERE calendar_date=?", Integer.class, date);
        return overrides.isEmpty() ? date.getDayOfWeek().getValue() <= 5 : overrides.get(0) == 1;
    }

    void save(AttendanceRule rule, LocalDate effectiveDate) {
        rule.validate();
        jdbc.update("""
                INSERT INTO attendance_rule_version(effective_date,work_start,work_end,late_warning,early_warning,
                    check_in_exception,check_out_exception,earliest_check_in,latest_check_out)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, effectiveDate, rule.start(), rule.end(), rule.lateWarning(), rule.earlyWarning(),
                rule.inException(), rule.outException(), rule.earliestIn(), rule.latestOut());
    }

    void setWorkday(LocalDate date, boolean workday) {
        jdbc.update("""
                INSERT INTO attendance_calendar_exception(calendar_date,workday) VALUES(?,?)
                ON DUPLICATE KEY UPDATE workday=?
                """, date, workday, workday);
    }

    List<Map<String,Object>> upcomingExceptions() {
        return jdbc.queryForList("""
                SELECT calendar_date,workday FROM attendance_calendar_exception
                WHERE calendar_date>=? ORDER BY calendar_date LIMIT 60
                """, now().toLocalDate());
    }
}
