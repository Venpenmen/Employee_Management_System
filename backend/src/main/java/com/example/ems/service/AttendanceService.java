package com.example.ems.service;

import com.example.ems.model.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class AttendanceService {
    @org.springframework.beans.factory.annotation.Autowired private SessionService sessions;
    private void guard(Long id,int role){if(sessions==null)throw new IllegalStateException("Session protection is not configured");sessions.guardCurrent(id,role);}
    private final JdbcTemplate jdbc;
    private final LogService logs;
    private final AttendanceCalendar calendar;

    public AttendanceService(JdbcTemplate jdbc, LogService logs, AttendanceCalendar calendar) {
        this.jdbc = jdbc;
        this.logs = logs;
        this.calendar = calendar;
    }

    public String currentMonth() {
        return YearMonth.from(calendar.now()).toString();
    }

    public Map<String,Object> rule(){
        AttendanceRule rule = calendar.rule(calendar.now().toLocalDate());
        return Map.of("work_start_time", rule.start(), "work_end_time", rule.end(),
                "late_grace_minutes", rule.lateWarning(), "early_grace_minutes", rule.earlyWarning(),
                "in_exception", rule.inException(), "out_exception", rule.outException(),
                "earliest_in", rule.earliestIn(), "latest_out", rule.latestOut(),
                "last_in", rule.lastIn(), "first_out", rule.firstOut());
    }

    public Map<String,Object> today(SessionUser user) {
        return record(user.id(), calendar.now().toLocalDate());
    }

    private Map<String,Object> record(long employee, LocalDate date) {
        return jdbc.queryForList("SELECT * FROM attendance_record WHERE employee_id=? AND attendance_date=?",
                employee, date).stream().findFirst().orElse(Map.of());
    }

    // Fully excused post-migration days are projected at read time.
    private static final String RECORDS = """
            WITH RECURSIVE dates(day) AS (
                SELECT GREATEST(CAST(? AS DATE), CAST(? AS DATE))
                UNION ALL SELECT day + INTERVAL 1 DAY FROM dates WHERE day < ?
            ), effective AS (
                SELECT dates.day, COALESCE(v.work_start,old.work_start_time) work_start,
                       COALESCE(v.work_end,old.work_end_time) work_end
                FROM dates
                LEFT JOIN attendance_calendar_exception c ON c.calendar_date=dates.day
                LEFT JOIN attendance_rule_version v ON v.id=(
                    SELECT id FROM attendance_rule_version WHERE effective_date<=dates.day
                    ORDER BY effective_date DESC,id DESC LIMIT 1)
                LEFT JOIN attendance_rule old ON old.id=(
                    SELECT id FROM attendance_rule WHERE status=1 ORDER BY id DESC LIMIT 1)
                WHERE dates.day<=? AND dates.day BETWEEN ? AND ?
                  AND COALESCE(c.workday,WEEKDAY(dates.day)<5)=1
            ), covered AS (
                SELECT l.employee_id,e.day,
                       MAX(l.start_time<=TIMESTAMP(e.day,e.work_start) AND l.end_time>=TIMESTAMP(e.day,e.work_start)) covered_in,
                       MAX(l.start_time<=TIMESTAMP(e.day,e.work_end) AND l.end_time>=TIMESTAMP(e.day,e.work_end)) covered_out
                FROM effective e JOIN leave_request l ON l.status=2
                    AND l.start_time<=TIMESTAMP(e.day,e.work_end) AND l.end_time>=TIMESTAMP(e.day,e.work_start)
                GROUP BY l.employee_id,e.day
            ), rows_for_month AS (
                SELECT a.id,a.employee_id,a.attendance_date,a.check_in_time,a.check_out_time,a.late_minutes,
                       a.early_leave_minutes,a.correction_reason,
                       CASE WHEN a.attendance_status=0 AND a.check_in_time IS NOT NULL AND a.check_out_time IS NULL
                                  AND c.covered_out=1 AND a.corrected_by IS NULL
                                  AND EXISTS(SELECT 1 FROM attendance_punch_event p WHERE p.record_id=a.id)
                                  AND NOT EXISTS(SELECT 1 FROM attendance_punch_event p
                                      JOIN attendance_exception_request r ON r.punch_id=p.id
                                      WHERE p.record_id=a.id AND r.status<>'APPROVED')
                            THEN 1 ELSE a.attendance_status END attendance_status
                FROM attendance_record a LEFT JOIN covered c ON c.employee_id=a.employee_id AND c.day=a.attendance_date
                WHERE a.attendance_date BETWEEN ? AND ?
                UNION ALL
                SELECT NULL,c.employee_id,c.day,NULL,NULL,0,0,NULL,6
                FROM covered c WHERE c.covered_in=1 AND c.covered_out=1
                  AND NOT EXISTS(SELECT 1 FROM attendance_record a WHERE a.employee_id=c.employee_id AND a.attendance_date=c.day)
            )
            """;

    private List<Object> monthParameters(String month) {
        YearMonth selected = YearMonth.parse(validMonth(month));
        LocalDate start = selected.atDay(1);
        LocalDate end = selected.atEndOfMonth();
        return new ArrayList<>(List.of(start, calendar.workflowStart(), end, calendar.now().toLocalDate(), start, end, start, end));
    }

    public List<Map<String,Object>> myRecords(SessionUser user, String month) {
        List<Object> args = monthParameters(month);
        args.add(user.id());
        return labelRecords(jdbc.queryForList(RECORDS +
                "SELECT * FROM rows_for_month WHERE employee_id=? ORDER BY attendance_date DESC", args.toArray()));
    }

    // The timeline remains authoritative; this is a read-only matrix label for both views.
    private List<Map<String,Object>> labelRecords(List<Map<String,Object>> records) {
        LocalDateTime now = calendar.now();
        for (Map<String,Object> row : records) {
            int status = number(row, "attendance_status");
            LocalDate date = ((java.sql.Date)row.get("attendance_date")).toLocalDate();
            boolean ended = now.isAfter(date.atTime(calendar.rule(date).latestOut()));
            boolean in = row.get("check_in_time") != null;
            boolean out = row.get("check_out_time") != null;
            boolean late = number(row, "late_minutes") > 0;
            boolean early = number(row, "early_leave_minutes") > 0;
            String label;
            if (status == 6) label = "请假";
            else if (status == 7) label = "旷工";
            else if (status == 8 || row.get("correction_reason") != null) label = "已修正";
            else if (status == 5 || (ended && in && !out)) {
                label = (late ? "迟到 · " : "") + "缺卡";
                if (out && !in) label += " · 异常签退";
            } else if (!out && in) label = status == 1 ? "签退请假 · 已完成" : (late ? "迟到 · " : "") + (ended ? "缺卡" : "待签退");
            else if (status == 0) label = "异常待审核 / 未完成";
            else if (status == 4) label = "迟到 · 早退";
            else if (status == 3) label = "早退";
            else if (status == 2) label = "迟到";
            else label = "正常";
            row.put("status_label", label);
        }
        return records;
    }

    public Map<String,Object> mySummary(SessionUser user, String month) {
        return summary(myRecords(user, month));
    }

    public Map<String,Object> summary(List<Map<String,Object>> records) {
        int normal = 0, late = 0, early = 0, missing = 0, leave = 0, absent = 0;
        for (var row : records) {
            int status = number(row, "attendance_status");
            if (status == 1) normal++;
            if (status == 2 || status == 4 || (status == 5 && number(row, "late_minutes") > 0)) late++;
            if (status == 3 || status == 4 || (status == 5 && number(row, "early_leave_minutes") > 0)) early++;
            if (status == 5) missing++;
            if (status == 6) leave++;
            if (status == 7) absent++;
        }
        return Map.of("total_days", records.size(), "normal_days", normal, "late_count", late,
                "early_count", early, "missing_count", missing, "leave_days", leave, "absent_days", absent);
    }

    public record PunchOptions(LocalDate date, String checkIn, String checkOut, boolean missingCheckIn) {
        public PunchOptions(LocalDate date, String checkIn, String checkOut) {
            this(date, checkIn, checkOut, false);
        }
    }

    private record Day(LocalDateTime now, AttendanceRule rule, Map<String,Object> record,
                       boolean workday, boolean excusedIn, boolean excusedOut) {
        AttendanceRule.Result option(boolean in) {
            if (!workday || (in ? excusedIn : excusedOut)) return AttendanceRule.Result.CLOSED;
            if (in) return record.get("check_in_time") == null
                    ? rule.checkIn(now.toLocalTime()) : AttendanceRule.Result.CLOSED;
            if (record.get("check_out_time") != null) return AttendanceRule.Result.CLOSED;
            AttendanceRule.Result window = rule.checkOut(now.toLocalTime());
            if (window == AttendanceRule.Result.CLOSED) return window;
            // Missing a check-in never produces a normal checkout result.
            return missingCheckIn() ? AttendanceRule.Result.EXCEPTION : window;
        }
        boolean missingCheckIn() {
            return !excusedIn && record.get("check_in_time") == null;
        }
    }

    private Day day(long employee, LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        AttendanceRule rule = calendar.rule(date);
        LocalDateTime start = date.atTime(rule.start());
        LocalDateTime end = date.atTime(rule.end());
        Map<String,Object> leave = jdbc.queryForMap("""
                SELECT COALESCE(MAX(start_time<=? AND end_time>=?),0) excused_in,
                       COALESCE(MAX(start_time<=? AND end_time>=?),0) excused_out
                FROM leave_request WHERE employee_id=? AND status=2 AND start_time<=? AND end_time>=?
                """, start, start, end, end, employee, end, start);
        return new Day(now, rule, record(employee, date), calendar.workday(date),
                number(leave, "excused_in") != 0, number(leave, "excused_out") != 0);
    }

    public PunchOptions punchOptions(SessionUser user) {
        Day day = day(user.id(), calendar.now());
        AttendanceRule.Result out = day.option(false);
        return new PunchOptions(day.now().toLocalDate(), day.option(true).name(), out.name(),
                out != AttendanceRule.Result.CLOSED && day.missingCheckIn());
    }

    @Transactional
    public boolean punch(SessionUser user, boolean in, String ip, String reason) {
        sessions.guardPersonal(user.id());
        Day day = day(user.id(), calendar.now());
        AttendanceRule.Result result = day.option(in);
        if (result == AttendanceRule.Result.CLOSED)
            throw new IllegalArgumentException("当前不能打卡。超出允许范围时请联系经理或高层处理");
        boolean exceptional = result == AttendanceRule.Result.EXCEPTION;
        String detail = exceptional ? requiredReason(reason) : null;
        LocalDate date = day.now().toLocalDate();
        jdbc.update("""
                INSERT INTO attendance_record(employee_id,attendance_date) VALUES(?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, user.id(), date);
        long recordId = jdbc.queryForObject("SELECT id FROM attendance_record WHERE employee_id=? AND attendance_date=?",
                Long.class, user.id(), date);
        String direction = in ? "IN" : "OUT";
        int deviation = day.rule().deviation(day.now().toLocalTime(), in);
        jdbc.update("""
                INSERT INTO attendance_punch_event(record_id,rule_version_id,legacy_rule_id,direction,punched_at,punched_ip,deviation_minutes)
                VALUES(?,?,?,?,?,?,?)
                """, recordId, day.rule().id() > 0 ? day.rule().id() : null,
                day.rule().id() < 0 ? -day.rule().id() : null, direction, day.now(), cleanIp(ip), deviation);
        if (exceptional) {
            jdbc.update("""
                    INSERT INTO attendance_exception_request(punch_id,applicant_id,status,reason,submitted_at)
                    SELECT id,?,'PENDING',?,? FROM attendance_punch_event WHERE record_id=? AND direction=?
                    """, user.id(), detail, day.now(), recordId, direction);
        }
        jdbc.update(in
                        ? "UPDATE attendance_record SET check_in_time=?,check_in_ip=?,late_minutes=? WHERE id=?"
                        : "UPDATE attendance_record SET check_out_time=?,check_out_ip=?,early_leave_minutes=? WHERE id=?",
                day.now(), cleanIp(ip), exceptional ? 0 : deviation, recordId);
        compose(recordId, user.id(), date);
        logs.log(user.id(), in ? "CHECK_IN" : "CHECK_OUT", "ATTENDANCE", recordId,
                exceptional ? "异常打卡待核实" : "打卡成功");
        return exceptional;
    }

    private void compose(long recordId, long employee, LocalDate date) {
        Day day = day(employee, date.atStartOfDay());
        Map<String,Object> record = day.record();
        List<String> states = jdbc.queryForList("""
                SELECT r.status FROM attendance_exception_request r JOIN attendance_punch_event p ON p.id=r.punch_id
                WHERE p.record_id=?
                """, String.class, recordId);
        int late = number(record, "late_minutes");
        int early = number(record, "early_leave_minutes");
        int status;
        if (record.get("check_out_time") != null && day.missingCheckIn()) status = 5;
        else if (states.contains("PENDING")) status = 0;
        else if (states.contains("REJECTED") || states.contains("CANCELLED")) status = 5;
        else if (record.get("check_out_time") == null && !day.excusedOut()) status = late > 0 ? 2 : 0;
        else status = late > 0 ? (early > 0 ? 4 : 2) : (early > 0 ? 3 : 1);
        jdbc.update("UPDATE attendance_record SET attendance_status=? WHERE id=? AND corrected_by IS NULL",
                status, recordId);
    }

    private static final String REVIEW_SCOPE = """
            reviewer.employment_status=1 AND applicant.employment_status=1 AND reviewer.id<>applicant.id
            AND (reviewer.user_type=2 OR (reviewer.user_type=1 AND applicant.user_type=0
                 AND reviewer.department_id=applicant.department_id))
            """;

    public List<Map<String,Object>> pendingReviews(SessionUser user) {
        return jdbc.queryForList("""
                SELECT r.id,r.reason,p.direction,p.punched_at,p.deviation_minutes,applicant.name,
                       (p.direction='OUT' AND a.check_in_time IS NULL) AS missing_check_in
                FROM attendance_exception_request r JOIN attendance_punch_event p ON p.id=r.punch_id
                JOIN attendance_record a ON a.id=p.record_id
                JOIN employee applicant ON applicant.id=r.applicant_id JOIN employee reviewer ON reviewer.id=?
                WHERE r.status='PENDING' AND
                """ + REVIEW_SCOPE + " ORDER BY r.submitted_at,r.id", user.id());
    }

    public boolean hasPendingReview(SessionUser user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM attendance_exception_request r
                JOIN employee applicant ON applicant.id=r.applicant_id JOIN employee reviewer ON reviewer.id=?
                WHERE r.status='PENDING' AND
                """ + REVIEW_SCOPE + ")", Boolean.class, user.id()));
    }

    public List<Map<String,Object>> myRequests(SessionUser user, Long requestId) {
        return jdbc.queryForList("""
                SELECT r.id,r.reason,r.status,r.decision,r.opinion,r.read_at,p.direction,p.punched_at,
                       (p.direction='OUT' AND a.check_in_time IS NULL) AS missing_check_in
                FROM attendance_exception_request r JOIN attendance_punch_event p ON p.id=r.punch_id
                JOIN attendance_record a ON a.id=p.record_id
                WHERE r.applicant_id=? AND (? IS NULL OR r.id=?) ORDER BY r.id DESC LIMIT 100
                """, user.id(), requestId, requestId);
    }

    public List<Map<String,Object>> personalActionRequests(SessionUser user) {
        return jdbc.queryForList("""
                SELECT r.id,r.status,p.direction FROM attendance_exception_request r
                JOIN attendance_punch_event p ON p.id=r.punch_id
                WHERE applicant_id=? AND (status='PENDING' OR (status IN ('APPROVED','REJECTED') AND read_at IS NULL))
                ORDER BY r.id
                """, user.id());
    }

    @Transactional
    public void review(SessionUser user, long id, String decision, String opinion) {
        guard(user.id(), 1);
        if (!Set.of("AS_RECORDED", "AS_NORMAL", "REJECT").contains(decision))
            throw new IllegalArgumentException("无效的审批结论");
        String detail = "AS_RECORDED".equals(decision) && (opinion == null || opinion.isBlank())
                ? null : requiredReason(opinion);
        Map<String,Object> request = jdbc.queryForList("""
                SELECT r.applicant_id,p.record_id,p.direction,p.deviation_minutes,a.attendance_date,a.check_in_time
                FROM attendance_exception_request r JOIN attendance_punch_event p ON p.id=r.punch_id
                JOIN attendance_record a ON a.id=p.record_id
                JOIN employee applicant ON applicant.id=r.applicant_id JOIN employee reviewer ON reviewer.id=?
                WHERE r.id=? AND r.status='PENDING' AND
                """ + REVIEW_SCOPE + " FOR UPDATE", user.id(), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("无权审批或该申请已处理"));
        if ("AS_NORMAL".equals(decision) && "OUT".equals(request.get("direction"))
                && request.get("check_in_time") == null) {
            long applicant = ((Number) request.get("applicant_id")).longValue();
            LocalDate requestDate = LocalDate.parse(request.get("attendance_date").toString());
            if (day(applicant, requestDate.atStartOfDay()).missingCheckIn())
                throw new IllegalArgumentException("缺少签到记录不能按正常通过，请按原情况通过或驳回");
        }
        boolean rejected = "REJECT".equals(decision);
        int changed = jdbc.update("""
                UPDATE attendance_exception_request
                SET status=?,decision=?,reviewer_id=?,opinion=?,decided_at=?
                WHERE id=? AND status='PENDING'
                """, rejected ? "REJECTED" : "APPROVED", decision, user.id(), detail, calendar.now(), id);
        if (changed != 1) throw new IllegalArgumentException("该申请已处理");
        long recordId = ((Number)request.get("record_id")).longValue();
        jdbc.update("IN".equals(request.get("direction"))
                        ? "UPDATE attendance_record SET late_minutes=? WHERE id=? AND corrected_by IS NULL"
                        : "UPDATE attendance_record SET early_leave_minutes=? WHERE id=? AND corrected_by IS NULL",
                "AS_RECORDED".equals(decision) ? number(request, "deviation_minutes") : 0, recordId);
        compose(recordId, ((Number)request.get("applicant_id")).longValue(),
                LocalDate.parse(request.get("attendance_date").toString()));
        logs.log(user.id(), "REVIEW_ATTENDANCE_EXCEPTION", "ATTENDANCE_EXCEPTION", id, decision);
    }

    @Transactional
    public void readResult(SessionUser user, long id) {
        sessions.guardPersonal(user.id());
        jdbc.update("""
                UPDATE attendance_exception_request SET read_at=?
                WHERE id=? AND applicant_id=? AND read_at IS NULL AND status IN ('APPROVED','REJECTED')
                """, calendar.now(), id, user.id());
    }

    private static int number(Map<String,Object> values, String field) {
        return ((Number)values.get(field)).intValue();
    }

    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new IllegalArgumentException("请填写原因，最多 500 字");
        return reason.trim();
    }

    public List<Map<String,Object>> manage(SessionUser user,String month,Long departmentId,String keyword){
        StringBuilder sql=new StringBuilder(RECORDS + """
            SELECT a.*,e.employee_no,e.name,d.department_name,
                   out_request.status AS check_out_review_status,
                   out_request.reason AS check_out_exception_reason
            FROM rows_for_month a
            JOIN employee e ON e.id=a.employee_id LEFT JOIN department d ON d.id=e.department_id
            LEFT JOIN attendance_punch_event out_event ON out_event.record_id=a.id AND out_event.direction='OUT'
            LEFT JOIN attendance_exception_request out_request ON out_request.punch_id=out_event.id
            WHERE 1=1
            """);
        List<Object> args=monthParameters(month);
        if(user.isManager()){sql.append(" AND e.department_id=?");args.add(user.departmentId());}
        else if(!user.isExecutive()) throw new IllegalArgumentException("无权查看考勤管理");
        if(user.isExecutive()&&departmentId!=null){sql.append(" AND e.department_id=?");args.add(departmentId);}
        if(keyword!=null&&!keyword.isBlank()){sql.append(" AND (e.employee_no LIKE ? OR e.name LIKE ?)");args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
        sql.append(" ORDER BY a.attendance_date DESC,e.employee_no");
        return labelRecords(jdbc.queryForList(sql.toString(),args.toArray()));
    }

    @Transactional
    public void correct(Long id,int status,String reason,SessionUser operator){
        guard(operator.id(),2);
        if(!operator.isExecutive()) throw new IllegalArgumentException("只有高层可以修正考勤");
        if(status<1||status>8) throw new IllegalArgumentException("考勤状态无效");
        if(reason==null||reason.isBlank()) throw new IllegalArgumentException("修正原因不能为空");
        if(jdbc.update("UPDATE attendance_record SET attendance_status=?,correction_reason=?,corrected_by=? WHERE id=?",status,reason.trim(),operator.id(),id)==0) throw new IllegalArgumentException("考勤记录不存在");
        logs.log(operator.id(),"CORRECT_ATTENDANCE","ATTENDANCE",id,"修正考勤："+reason.trim());
    }

    @Transactional
    public void updateRule(LocalTime start, LocalTime end, int lateWarning, int earlyWarning,
                           int inException, int outException, LocalTime earliestIn, LocalTime latestOut,
                           SessionUser operator) {
        guard(operator.id(), 2);
        LocalDate effective = calendar.now().toLocalDate().plusDays(1);
        calendar.save(new AttendanceRule(0, start, end, lateWarning, earlyWarning,
                inException, outException, earliestIn, latestOut), effective);
        logs.log(operator.id(), "UPDATE_ATTENDANCE_RULE", "ATTENDANCE_RULE", null, "考勤规则生效日 " + effective);
    }

    public List<Map<String,Object>> calendarExceptions() {
        return calendar.upcomingExceptions();
    }

    @Transactional
    public void setWorkday(LocalDate date, boolean workday, SessionUser operator) {
        guard(operator.id(), 2);
        if (!date.isAfter(calendar.now().toLocalDate()))
            throw new IllegalArgumentException("仅可调整未来日期");
        calendar.setWorkday(date, workday);
        logs.log(operator.id(), "UPDATE_ATTENDANCE_CALENDAR", "ATTENDANCE_RULE", null,
                date + (workday ? " 为工作日" : " 为休息日"));
    }

    private String validMonth(String month) {
        return month != null && month.matches("[0-9]{4}-(0[1-9]|1[0-2])") ? month : currentMonth();
    }
    private String cleanIp(String ip){return ip==null?null:(ip.length()>45?ip.substring(0,45):ip);}
}
