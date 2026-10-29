package com.example.ems.service;

import com.example.ems.model.SessionUser;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class LeaveService {
    @org.springframework.beans.factory.annotation.Autowired private SessionService sessions;
    private void guard(Long id,int role){if(sessions==null)throw new IllegalStateException("Session protection is not configured");sessions.guardCurrent(id,role);}
    private final JdbcTemplate jdbc; private final LogService logs;
    public LeaveService(JdbcTemplate jdbc,LogService logs){this.jdbc=jdbc;this.logs=logs;}

    public List<Map<String,Object>> types(){return jdbc.queryForList("SELECT item_value,item_label FROM dictionary_item WHERE dict_type='leave_type' AND status=1 ORDER BY sort_order,id");}
    public List<Map<String,Object>> mine(SessionUser u){return jdbc.queryForList(baseSql()+" WHERE l.employee_id=? ORDER BY l.id DESC",u.id());}
    public List<Map<String,Object>> pending(SessionUser u){
        if(!u.isManager()&&!u.isExecutive()) throw new IllegalArgumentException("无审批权限");
        return jdbc.queryForList(baseSql()+" WHERE l.current_approver_id=? AND l.status=1 ORDER BY l.submit_time",u.id());
    }
    public List<Map<String,Object>> history(String requestNo,SessionUser u){
        Map<String,Object> request=get(requestNo);checkReadable(request,u);
        return jdbc.queryForList("SELECT r.*,e.employee_no,e.name approver_name FROM leave_approval_record r JOIN employee e ON e.id=r.approver_id WHERE r.leave_request_id=? ORDER BY r.id",request.get("id"));
    }

    @Transactional
    public String submit(String type,LocalDateTime start,LocalDateTime end,String reason,SessionUser u){
        if(start==null||end==null||!end.isAfter(start)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
        if(reason==null||reason.isBlank()) throw new IllegalArgumentException("请假原因不能为空");
        jdbc.queryForObject("SELECT id FROM employee WHERE id=? FOR UPDATE",Long.class,u.id());
        Integer valid=jdbc.queryForObject("SELECT COUNT(*) FROM dictionary_item WHERE dict_type='leave_type' AND item_value=? AND status=1",Integer.class,type);
        if(valid==null||valid==0) throw new IllegalArgumentException("请假类型无效");
        Integer overlap=jdbc.queryForObject("SELECT COUNT(*) FROM leave_request WHERE employee_id=? AND status IN(1,2) AND start_time<? AND end_time>?",Integer.class,u.id(),end,start);
        if(overlap!=null&&overlap>0) throw new IllegalArgumentException("该时间段与已有待审批或已通过的请假重叠");
        Long approver=findApprover(u); int status=approver==null?2:1;
        BigDecimal hours=BigDecimal.valueOf(Duration.between(start,end).toMinutes()).divide(BigDecimal.valueOf(60),2,RoundingMode.HALF_UP);
        String requestNo="L"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        jdbc.update("INSERT INTO leave_request(request_no,employee_id,leave_type,start_time,end_time,leave_hours,reason,status,current_approver_id,finish_time) VALUES(?,?,?,?,?,?,?,?,?,?)",
                requestNo,u.id(),type,start,end,hours,reason.trim(),status,approver,status==2?LocalDateTime.now():null);
        Long id=jdbc.queryForObject("SELECT id FROM leave_request WHERE request_no=?",Long.class,requestNo);
        jdbc.update("INSERT INTO leave_approval_record(leave_request_id,approver_id,action,before_status,after_status,opinion) VALUES(?,?,'SUBMIT',NULL,?,?)",id,u.id(),status,approver==null?"系统仅有一名高层，自动通过":"提交申请");
        if(status==2) markLeaveAttendance(u.id(),start.toLocalDate(),end.toLocalDate());
        logs.log(u.id(),"SUBMIT_LEAVE","LEAVE",id,"提交请假申请："+requestNo);
        return requestNo;
    }

    @Transactional
    public void decide(String requestNo,boolean approve,String opinion,SessionUser u){
        guard(u.id(),1);
        Map<String,Object> r=jdbc.queryForMap("SELECT * FROM leave_request WHERE request_no=? FOR UPDATE",requestNo);Long id=((Number)r.get("id")).longValue();
        if(((Number)r.get("status")).intValue()!=1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"该申请已被处理");
        if(r.get("current_approver_id")==null||!u.id().equals(((Number)r.get("current_approver_id")).longValue())) throw new IllegalArgumentException("您不是当前审批人");
        Long applicant=((Number)r.get("employee_id")).longValue();
        if(applicant.equals(u.id()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"不得审批自己的申请");
        var actor=jdbc.queryForMap("SELECT user_type,department_id FROM employee WHERE id=? FOR UPDATE",u.id());
        Long actorDept=actor.get("department_id")==null?null:((Number)actor.get("department_id")).longValue();
        if(((Number)actor.get("user_type")).intValue()!=u.userType()||!java.util.Objects.equals(actorDept,u.departmentId()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"权限或部门已经变化，请刷新");
        if(u.isManager()){
            var person=jdbc.queryForMap("SELECT user_type,department_id FROM employee WHERE id=?",applicant);
            Long dept=person.get("department_id")==null?null:((Number)person.get("department_id")).longValue();
            if(((Number)person.get("user_type")).intValue()!=0||actorDept==null||!actorDept.equals(dept))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"只能审批本部门普通员工的申请");
        }
        if(!approve&&(opinion==null||opinion.isBlank())) throw new IllegalArgumentException("驳回时必须填写审批意见");
        int next=approve?2:3;
        int updated=jdbc.update("UPDATE leave_request SET status=?,current_approver_id=NULL,finish_time=NOW() WHERE id=? AND status=1 AND current_approver_id=?",next,id,u.id());
        if(updated==0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"申请已被其他人处理，请刷新页面");
        jdbc.update("INSERT INTO leave_approval_record(leave_request_id,approver_id,action,before_status,after_status,opinion) VALUES(?,?,?,?,?,?)",id,u.id(),approve?"APPROVE":"REJECT",1,next,opinion);
        if(approve) markLeaveAttendance(((Number)r.get("employee_id")).longValue(),toLocalDateTime(r.get("start_time")).toLocalDate(),toLocalDateTime(r.get("end_time")).toLocalDate());
        logs.log(u.id(),approve?"APPROVE_LEAVE":"REJECT_LEAVE","LEAVE",id,(approve?"通过":"驳回")+"请假申请："+requestNo);
    }

    @Transactional
    public void withdraw(String requestNo,SessionUser u){
        Map<String,Object> r=jdbc.queryForMap("SELECT * FROM leave_request WHERE request_no=? FOR UPDATE",requestNo);Long id=((Number)r.get("id")).longValue();
        if(!u.id().equals(((Number)r.get("employee_id")).longValue())) throw new IllegalArgumentException("只能撤回自己的申请");
        if(jdbc.update("UPDATE leave_request SET status=4,current_approver_id=NULL,finish_time=NOW() WHERE id=? AND status=1",id)==0) throw new IllegalArgumentException("只有待审批申请可以撤回");
        jdbc.update("INSERT INTO leave_approval_record(leave_request_id,approver_id,action,before_status,after_status,opinion) VALUES(?,?, 'WITHDRAW',1,4,'申请人撤回')",id,u.id());
        logs.log(u.id(),"WITHDRAW_LEAVE","LEAVE",id,"撤回请假申请："+requestNo);
    }

    private Long findApprover(SessionUser u){
        if(u.isManager()) return jdbc.query("SELECT id FROM employee WHERE user_type=2 AND employment_status=1 AND id<>? ORDER BY id LIMIT 1",rs->rs.next()?rs.getLong(1):null,u.id());
        if(u.isExecutive()) return jdbc.query("SELECT id FROM employee WHERE user_type=2 AND employment_status=1 AND id<>? ORDER BY id LIMIT 1",rs->rs.next()?rs.getLong(1):null,u.id());
        if(u.departmentId()==null) throw new IllegalArgumentException("您尚未分配部门，无法确定审批人");
        Long manager=jdbc.query("SELECT manager_id FROM department WHERE id=? AND status=1",rs->rs.next()?(Long)rs.getObject(1):null,u.departmentId());
        if(manager==null) throw new IllegalArgumentException("本部门尚未任命经理，暂时无法提交请假");
        if(manager.equals(u.id())) throw new IllegalArgumentException("不能审批自己的申请");
        return manager;
    }
    private Map<String,Object> get(String no){try{return jdbc.queryForMap("SELECT * FROM leave_request WHERE request_no=?",no);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("请假申请不存在");}}
    private void checkReadable(Map<String,Object> r,SessionUser u){Long employee=((Number)r.get("employee_id")).longValue();Long approver=r.get("current_approver_id")==null?null:((Number)r.get("current_approver_id")).longValue();if(!u.isExecutive()&&!u.id().equals(employee)&&!u.id().equals(approver))throw new IllegalArgumentException("无权查看该申请");}
    private String baseSql(){return "SELECT l.*,e.employee_no,e.name,d.department_name,di.item_label leave_type_label,a.name approver_name FROM leave_request l JOIN employee e ON e.id=l.employee_id LEFT JOIN department d ON d.id=e.department_id LEFT JOIN dictionary_item di ON di.dict_type='leave_type' AND di.item_value=l.leave_type LEFT JOIN employee a ON a.id=l.current_approver_id";}
    private LocalDateTime toLocalDateTime(Object value){
        if(value instanceof LocalDateTime dateTime) return dateTime;
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if(value instanceof java.util.Date date) return new java.sql.Timestamp(date.getTime()).toLocalDateTime();
        if(value instanceof CharSequence text) return LocalDateTime.parse(text.toString().replace(' ','T'));
        throw new IllegalStateException("无法识别数据库日期时间类型："+(value==null?"null":value.getClass().getName()));
    }
    private void markLeaveAttendance(Long employeeId,LocalDate start,LocalDate end){for(LocalDate d=start;!d.isAfter(end);d=d.plusDays(1)){jdbc.update("INSERT INTO attendance_record(employee_id,attendance_date,attendance_status) VALUES(?,?,6) ON DUPLICATE KEY UPDATE attendance_status=IF(check_in_time IS NULL AND check_out_time IS NULL,6,attendance_status)",employeeId,d);}}
}
