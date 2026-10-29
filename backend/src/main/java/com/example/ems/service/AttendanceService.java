package com.example.ems.service;

import com.example.ems.model.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AttendanceService {
    @org.springframework.beans.factory.annotation.Autowired private SessionService sessions;
    private void guard(Long id,int role){if(sessions==null)throw new IllegalStateException("Session protection is not configured");sessions.guardCurrent(id,role);}
    private final JdbcTemplate jdbc;
    private final LogService logs;
    public AttendanceService(JdbcTemplate jdbc,LogService logs){this.jdbc=jdbc;this.logs=logs;}

    public Map<String,Object> rule(){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM attendance_rule WHERE status=1 ORDER BY id DESC LIMIT 1");
        if(rows.isEmpty()) throw new IllegalStateException("尚未配置启用的考勤规则");
        return rows.get(0);
    }

    public Map<String,Object> today(SessionUser user){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM attendance_record WHERE employee_id=? AND attendance_date=CURDATE()",user.id());
        return rows.isEmpty()?Map.of():rows.get(0);
    }

    public List<Map<String,Object>> myRecords(SessionUser user,String month){
        String m=validMonth(month);
        return jdbc.queryForList("SELECT * FROM attendance_record WHERE employee_id=? AND DATE_FORMAT(attendance_date,'%Y-%m')=? ORDER BY attendance_date DESC",user.id(),m);
    }

    public Map<String,Object> mySummary(SessionUser user,String month){
        String m=validMonth(month);
        return jdbc.queryForMap("""
            SELECT COUNT(*) total_days,
                   COALESCE(SUM(attendance_status=1),0) normal_days,
                   COALESCE(SUM(attendance_status IN(2,4)),0) late_count,
                   COALESCE(SUM(attendance_status IN(3,4)),0) early_count,
                   COALESCE(SUM(attendance_status=5),0) missing_count,
                   COALESCE(SUM(attendance_status=6),0) leave_days
            FROM attendance_record WHERE employee_id=? AND DATE_FORMAT(attendance_date,'%Y-%m')=?
            """,user.id(),m);
    }

    @Transactional
    public void checkIn(SessionUser user,String ip){
        jdbc.queryForObject("SELECT id FROM employee WHERE id=? FOR UPDATE",Long.class,user.id());
        if(!today(user).isEmpty()) throw new IllegalArgumentException("今天已经完成上班打卡，不能重复打卡");
        LocalDateTime now=LocalDateTime.now(); Map<String,Object> r=rule();
        LocalTime start=toLocalTime(r.get("work_start_time")).plusMinutes(((Number)r.get("late_grace_minutes")).longValue());
        int late=now.toLocalTime().isAfter(start)?(int)ChronoUnit.MINUTES.between(start,now.toLocalTime()):0;
        int status=late>0?2:0;
        jdbc.update("INSERT INTO attendance_record(employee_id,attendance_date,check_in_time,check_in_ip,attendance_status,late_minutes) VALUES(?,CURDATE(),?,?,?,?)",user.id(),now,cleanIp(ip),status,late);
        logs.log(user.id(),"CHECK_IN","ATTENDANCE",null,"上班打卡"+(late>0?"，迟到"+late+"分钟":""));
    }

    @Transactional
    public void checkOut(SessionUser user,String ip){
        jdbc.queryForObject("SELECT id FROM employee WHERE id=? FOR UPDATE",Long.class,user.id());
        Map<String,Object> record=today(user);
        if(record.isEmpty()||record.get("check_in_time")==null) throw new IllegalArgumentException("请先完成上班打卡");
        if(record.get("check_out_time")!=null) throw new IllegalArgumentException("今天已经完成下班打卡，不能重复打卡");
        LocalDateTime now=LocalDateTime.now(); Map<String,Object> r=rule();
        LocalTime end=toLocalTime(r.get("work_end_time")).minusMinutes(((Number)r.get("early_grace_minutes")).longValue());
        int early=now.toLocalTime().isBefore(end)?(int)ChronoUnit.MINUTES.between(now.toLocalTime(),end):0;
        int late=((Number)record.get("late_minutes")).intValue();
        int status=late>0&&early>0?4:(late>0?2:(early>0?3:1));
        jdbc.update("UPDATE attendance_record SET check_out_time=?,check_out_ip=?,attendance_status=?,early_leave_minutes=? WHERE id=?",now,cleanIp(ip),status,early,record.get("id"));
        logs.log(user.id(),"CHECK_OUT","ATTENDANCE",((Number)record.get("id")).longValue(),"下班打卡"+(early>0?"，早退"+early+"分钟":""));
    }

    public List<Map<String,Object>> manage(SessionUser user,String month,Long departmentId,String keyword){
        StringBuilder sql=new StringBuilder("""
            SELECT a.*,e.employee_no,e.name,d.department_name FROM attendance_record a
            JOIN employee e ON e.id=a.employee_id LEFT JOIN department d ON d.id=e.department_id
            WHERE DATE_FORMAT(a.attendance_date,'%Y-%m')=?
            """);
        List<Object> args=new ArrayList<>();args.add(validMonth(month));
        if(user.isManager()){sql.append(" AND e.department_id=?");args.add(user.departmentId());}
        else if(!user.isExecutive()) throw new IllegalArgumentException("无权查看考勤管理");
        if(user.isExecutive()&&departmentId!=null){sql.append(" AND e.department_id=?");args.add(departmentId);}
        if(keyword!=null&&!keyword.isBlank()){sql.append(" AND (e.employee_no LIKE ? OR e.name LIKE ?)");args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
        sql.append(" ORDER BY a.attendance_date DESC,e.employee_no");
        return jdbc.queryForList(sql.toString(),args.toArray());
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
    public void updateRule(LocalTime start,LocalTime end,int lateGrace,int earlyGrace,SessionUser operator){
        guard(operator.id(),2);
        if(!operator.isExecutive()) throw new IllegalArgumentException("只有高层可以修改考勤规则");
        if(!end.isAfter(start)) throw new IllegalArgumentException("下班时间必须晚于上班时间");
        if(lateGrace<0||lateGrace>120||earlyGrace<0||earlyGrace>120) throw new IllegalArgumentException("允许偏差分钟数必须在0到120之间");
        jdbc.update("UPDATE attendance_rule SET status=0 WHERE status=1");
        jdbc.update("INSERT INTO attendance_rule(rule_name,work_start_time,work_end_time,late_grace_minutes,early_grace_minutes,status) VALUES('默认工作制',?,?,?,?,1)",start,end,lateGrace,earlyGrace);
        logs.log(operator.id(),"UPDATE_ATTENDANCE_RULE","ATTENDANCE_RULE",null,"更新考勤规则");
    }

    private String validMonth(String month){return month!=null&&month.matches("\\d{4}-(0[1-9]|1[0-2])")?month:YearMonth.now().toString();}
    private LocalTime toLocalTime(Object value){
        if(value instanceof LocalTime time) return time;
        if(value instanceof java.sql.Time time) return time.toLocalTime();
        if(value instanceof CharSequence text) return LocalTime.parse(text.toString());
        throw new IllegalStateException("无法识别数据库时间类型："+(value==null?"null":value.getClass().getName()));
    }
    private String cleanIp(String ip){return ip==null?null:(ip.length()>45?ip.substring(0,45):ip);}
}
