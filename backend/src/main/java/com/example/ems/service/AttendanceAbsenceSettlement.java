package com.example.ems.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a no-punch absence only after that date's final checkout window closes.
 * Does not change partial punches, approved leave, or existing manual corrections.
 */
@Component
public class AttendanceAbsenceSettlement {
    private final JdbcTemplate jdbc;
    private final AttendanceCalendar calendar;

    AttendanceAbsenceSettlement(JdbcTemplate jdbc, AttendanceCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    // Shared by scheduled reconciliation and attendance views; all time checks use the injected clock.
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void scheduledReconcile() {
        reconcile();
    }

    public void reconcile() {
        LocalDateTime now = calendar.now();
        LocalDate first = calendar.workflowStart();
        // Never retroactively classify the entire historical database on the first deployment.
        // Absence settlement starts on the day this feature is first seen by this installation.
        String key = "absence_settlement_start";
        jdbc.update("""
            INSERT INTO dictionary_item(dict_type,item_value,item_label,sort_order,status)
            VALUES('__attendance_internal',?, ?,0,0)
            ON DUPLICATE KEY UPDATE item_label=item_label
            """, key, now.toLocalDate().toString());
        String startText = jdbc.queryForObject("""
            SELECT item_label FROM dictionary_item
            WHERE dict_type='__attendance_internal' AND item_value=?
            """, String.class, key);
        if (startText == null) return;
        LocalDate start = LocalDate.parse(startText);
        if (start.isBefore(first)) start = first;
        // Bounded backfill per run. Progress is advanced only after a successful pass.
        String cursorKey = "absence_settlement_cursor";
        jdbc.update("""
            INSERT INTO dictionary_item(dict_type,item_value,item_label,sort_order,status)
            VALUES('__attendance_internal',?, ?,0,0)
            ON DUPLICATE KEY UPDATE item_label=item_label
            """, cursorKey, start.toString());
        String cursorText = jdbc.queryForObject("""
            SELECT item_label FROM dictionary_item
            WHERE dict_type='__attendance_internal' AND item_value=?
            """, String.class, cursorKey);
        if (cursorText == null) return;
        LocalDate cursor = LocalDate.parse(cursorText);
        if (cursor.isBefore(start)) cursor = start;
        LocalDate end = now.toLocalDate();
        int inspected = 0;
        while (!cursor.isAfter(end) && inspected++ < 45) {
            if (calendar.workday(cursor) && !now.isAfter(cursor.atTime(calendar.rule(cursor).latestOut()))) break;
            settleDate(cursor, now);
            jdbc.update("""
                UPDATE dictionary_item SET item_label=? WHERE
                dict_type='__attendance_internal' AND item_value=?
                """, cursor.plusDays(1).toString(), cursorKey);
            cursor = cursor.plusDays(1);
        }
    }

    @Transactional
    public int settleDate(LocalDate date, LocalDateTime now) {
        if (date.isAfter(now.toLocalDate()) || date.isBefore(calendar.workflowStart())) return 0;
        if (!calendar.workday(date)) return 0;
        AttendanceRule rule = calendar.rule(date);
        if (!now.isAfter(date.atTime(rule.latestOut()))) return 0;

        // Finalize partial punches without changing punch timestamps or manual corrections.
        jdbc.update("""
            UPDATE attendance_record SET attendance_status=5
            WHERE attendance_date=? AND check_in_time IS NOT NULL AND check_out_time IS NULL
              AND corrected_by IS NULL AND attendance_status IN (0,2)
              AND NOT EXISTS(
                SELECT 1 FROM attendance_exception_request r
                JOIN attendance_punch_event p ON p.id=r.punch_id
                WHERE p.record_id=attendance_record.id AND r.status='PENDING'
              )
              AND NOT EXISTS(
                SELECT 1 FROM leave_request l WHERE l.employee_id=attendance_record.employee_id
                  AND l.status=2 AND l.start_time<=? AND l.end_time>=?
              )
            """, date, date.atTime(rule.end()), date.atTime(rule.end()));
        List<Long> ids = jdbc.queryForList("""
            SELECT e.id FROM employee e
            JOIN user_account a ON a.employee_id=e.id
            WHERE e.employment_status=1 AND a.account_status=1
              AND e.hire_date<=? AND (e.leave_date IS NULL OR e.leave_date>=?)
              AND e.user_type IN(0,1)
              AND NOT EXISTS(SELECT 1 FROM attendance_record r
                             WHERE r.employee_id=e.id AND r.attendance_date=?)
              AND NOT EXISTS(SELECT 1 FROM leave_request l
                    WHERE l.employee_id=e.id AND l.status=2
                      AND l.start_time<=? AND l.end_time>=?)
            """, Long.class, date, date, date,
            date.atTime(rule.start()), date.atTime(rule.end()));
        int inserted = 0;
        for (Long employeeId : ids) {
            inserted += jdbc.update("""
                INSERT IGNORE INTO attendance_record(employee_id,attendance_date,attendance_status)
                VALUES(?,?,7)
                """, employeeId, date);
        }
        return inserted;
    }
}
