package com.example.ems.service;

import com.example.ems.model.SessionUser;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AttendanceWorkflowIT {
    @Test void punchReviewLeaveAndVersion() throws Exception {
        assertEquals("16628", System.getenv("EMS_ATT_ISOLATED_PORT"));
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:16628/ems_next_dev_v121_attfresh", "root", "");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var sessions = mock(SessionService.class);
        doAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM employee WHERE id=? FOR UPDATE", Long.class, call.getArgument(0, Long.class));
            return null;
        }).when(sessions).guardPersonal(anyLong());
        var logs = mock(LogService.class);
        var staff = fixture(jdbc,0);
        var executive = fixture(jdbc,2);
        var leave = fixture(jdbc,0);
        var partial = fixture(jdbc,0);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (date.getDayOfWeek().getValue() > 5) {
            jdbc.update("INSERT INTO attendance_calendar_exception(calendar_date,workday) VALUES(?,1) ON DUPLICATE KEY UPDATE workday=1", date);
        }
        jdbc.update("""
                INSERT INTO attendance_rule_version(effective_date,work_start,work_end,late_warning,early_warning,
                  check_in_exception,check_out_exception,earliest_check_in,latest_check_out)
                VALUES(?,'09:00','17:00',5,5,30,30,'08:00','20:00')
                """, date);
        var early = service(jdbc, logs, sessions, date.atTime(9,10));
        assertThrows(IllegalArgumentException.class, () -> tx.execute(s -> early.punch(staff,true,"127.0.0.1",null)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> punch = () -> {
                try {
                    return tx.execute(s -> early.punch(staff,true,"127.0.0.1","Test exception"));
                } catch (IllegalArgumentException duplicate) {
                    return false;
                }
            };
            int successes = 0;
            for (var result : pool.invokeAll(java.util.List.of(punch,punch))) if (result.get()) successes++;
            assertEquals(1, successes);
        } finally {
            pool.shutdownNow();
        }
        var late = service(jdbc, logs, sessions, date.atTime(17,0));
        tx.executeWithoutResult(s -> early.updateRule(LocalTime.of(10,0),LocalTime.of(18,0),5,5,30,30,
                LocalTime.of(8,0),LocalTime.of(20,0),executive));
        assertEquals(LocalTime.of(17,0), late.rule().get("work_end_time"));
        assertEquals(LocalTime.of(18,0), service(jdbc, logs, sessions, date.plusDays(1).atTime(12,0)).rule().get("work_end_time"));
        assertEquals(false, tx.execute(s -> late.punch(staff,false,"127.0.0.1",null)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT rule_version_id) FROM attendance_punch_event p JOIN attendance_record a ON a.id=p.record_id WHERE a.employee_id=?", Integer.class, staff.id()));
        long request = ((Number)late.myRequests(staff,null).get(0).get("id")).longValue();
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s -> late.review(staff,request,"AS_NORMAL","test")));
        assertTrue(late.hasPendingReview(executive));
        tx.executeWithoutResult(s -> late.review(executive,request,"AS_NORMAL","approved"));
        assertTrue(late.pendingReviews(executive).stream().noneMatch(row -> ((Number)row.get("id")).longValue() == request));
        assertEquals(1, ((Number)late.today(staff).get("attendance_status")).intValue());
        assertEquals(1, late.personalActionRequests(staff).size());
        tx.executeWithoutResult(s -> late.readResult(staff,request));
        assertTrue(late.personalActionRequests(staff).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s -> late.review(executive,request,"REJECT","duplicate")));

        approvedLeave(jdbc, leave.id(),date.atTime(9,0),date.atTime(17,0));
        approvedLeave(jdbc, partial.id(),date.atTime(8,0),date.atTime(10,0));
        assertEquals("CLOSED", early.punchOptions(leave).checkIn());
        assertEquals("CLOSED", late.punchOptions(leave).checkOut());
        assertEquals(1, late.mySummary(leave,null).get("leave_days"));
        // A pre-V6 month must not inherit today's projected leave row.
        String previousMonth = YearMonth.from(date.minusMonths(1)).toString();
        assertTrue(late.myRecords(leave, previousMonth).isEmpty());
        assertEquals(0, late.mySummary(leave, previousMonth).get("leave_days"));
        assertTrue(late.today(leave).isEmpty());
        assertEquals("NORMAL",late.punchOptions(partial).checkOut());
        assertEquals(false, tx.execute(s -> late.punch(partial,false,"127.0.0.1",null)));
        assertEquals(1, ((Number)late.today(partial).get("attendance_status")).intValue());
    }

    @Test void reviewerScopeAndOtherDecisions() throws Exception {
        assertEquals("16628", System.getenv("EMS_ATT_ISOLATED_PORT"));
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:16628/ems_next_dev_v121_attfresh", "root", "");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var sessions = mock(SessionService.class);
        var logs = mock(LogService.class);
        var staff = fixture(jdbc,0);
        var manager = fixture(jdbc,1);
        var executive = fixture(jdbc,2);
        var other = fixture(jdbc,2);
        String dept = "att-" + java.util.UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO department(department_name,status) VALUES(?,1)", dept);
        long department = jdbc.queryForObject("SELECT id FROM department WHERE department_name=?",Long.class,dept);
        jdbc.update("UPDATE employee SET department_id=? WHERE id IN(?,?)",department,staff.id(),manager.id());
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("""
                INSERT INTO attendance_rule_version(effective_date,work_start,work_end,late_warning,early_warning,
                  check_in_exception,check_out_exception,earliest_check_in,latest_check_out)
                VALUES(?,'09:00','17:00',5,5,30,30,'08:00','20:00')
                """, date);
        jdbc.update("INSERT INTO attendance_calendar_exception(calendar_date,workday) VALUES(?,1) ON DUPLICATE KEY UPDATE workday=1",date);
        var in = service(jdbc,logs,sessions,date.atTime(9,10));
        var out = service(jdbc,logs,sessions,date.atTime(16,30));
        tx.execute(s -> in.punch(staff,true,null,"late"));
        tx.execute(s -> out.punch(staff,false,null,"early"));
        long outRequest = ((Number)out.myRequests(staff,null).get(0).get("id")).longValue();
        long inRequest = ((Number)out.myRequests(staff,null).get(1).get("id")).longValue();
        assertTrue(out.pendingReviews(manager).stream().anyMatch(row -> ((Number)row.get("id")).longValue()==outRequest));
        jdbc.update("UPDATE employee SET department_id=NULL WHERE id=?",staff.id());
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s -> out.review(manager,outRequest,"REJECT","moved")));
        jdbc.update("UPDATE employee SET employment_status=3 WHERE id=?",staff.id());
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s -> out.review(executive,inRequest,"AS_RECORDED",null)));
        jdbc.update("UPDATE employee SET employment_status=1 WHERE id=?",staff.id());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int successes = 0;
            for (var result : pool.invokeAll(java.util.List.of(
                    decide(tx,out,executive,inRequest),decide(tx,out,other,inRequest)))) {
                if (result.get()) successes++;
            }
            assertEquals(1,successes);
        } finally {
            pool.shutdownNow();
        }
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s -> out.review(executive,outRequest,"REJECT"," ")));
        tx.executeWithoutResult(s -> out.review(executive,outRequest,"REJECT","Rejected fixture"));
        var day = out.today(staff);
        assertEquals(10,((Number)day.get("late_minutes")).intValue());
        assertEquals(5,((Number)day.get("attendance_status")).intValue());
        assertNotNull(day.get("check_out_time"));

        tx.execute(s -> in.punch(executive,true,null,"Executive fixture"));
        long self = ((Number)in.myRequests(executive,null).get(0).get("id")).longValue();
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s -> in.review(executive,self,"AS_NORMAL","self")));
        assertEquals("PENDING",in.myRequests(executive,self).get(0).get("status"));
        tx.executeWithoutResult(s -> in.review(other,self,"AS_NORMAL","Approved by another executive"));
    }

    private static Callable<Boolean> decide(TransactionTemplate tx, AttendanceService service, SessionUser reviewer, long id) {
        return () -> {
            try {
                tx.executeWithoutResult(s -> service.review(reviewer,id,"AS_RECORDED",null));
                return true;
            } catch (IllegalArgumentException alreadyDecided) {
                return false;
            }
        };
    }

    private static AttendanceService service(JdbcTemplate jdbc, LogService logs, SessionService sessions, LocalDateTime at) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        var calendar = new AttendanceCalendar(jdbc,Clock.fixed(at.atZone(zone).toInstant(),zone));
        var service = new AttendanceService(jdbc,logs,calendar);
        ReflectionTestUtils.setField(service,"sessions",sessions);
        return service;
    }

    private static SessionUser fixture(JdbcTemplate jdbc, int role) {
        String token = java.util.UUID.randomUUID().toString();
        String employeeNo = token.substring(0,6);
        jdbc.update("""
                INSERT INTO employee(employee_no,name,identity_number,birth_date,hire_date,employment_status,user_type)
                VALUES(?,'Synthetic',?,'1990-01-01','2020-01-01',1,?)
                """, employeeNo, token, role);
        long id = jdbc.queryForObject("SELECT id FROM employee WHERE identity_number=?", Long.class, token);
        return new SessionUser(id,employeeNo,"Synthetic",role,null,null);
    }

    private static void approvedLeave(JdbcTemplate jdbc,long id,LocalDateTime start,LocalDateTime end) {
        jdbc.update("""
                INSERT INTO leave_request(request_no,employee_id,leave_type,start_time,end_time,leave_hours,reason,status)
                VALUES(?,?,'annual',?,?,1,'synthetic',2)
                ""","att-leave-"+id,id,start,end);
    }
}
