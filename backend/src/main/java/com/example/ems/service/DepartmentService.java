package com.example.ems.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class DepartmentService {
    @org.springframework.beans.factory.annotation.Autowired private SessionService sessions;
    private void guard(Long id,int role){if(sessions==null)throw new IllegalStateException("Session protection is not configured");sessions.guardCurrent(id,role);}
    private final JdbcTemplate jdbc;
    private final LogService logService;
    public DepartmentService(JdbcTemplate jdbc, LogService logService) { this.jdbc = jdbc; this.logService = logService; }

    public List<Map<String,Object>> listDepartments() {
        return jdbc.queryForList("""
            SELECT d.*,e.name manager_name,e.employee_no manager_no,
                   (SELECT COUNT(*) FROM employee x WHERE x.department_id=d.id AND x.employment_status IN(0,1,2)) employee_count
            FROM department d LEFT JOIN employee e ON e.id=d.manager_id
            WHERE d.status<>2 ORDER BY d.id
            """);
    }
    public List<Map<String,Object>> listPositions() {
        return jdbc.queryForList("SELECT p.*,d.department_name,d.base_salary AS department_base_salary,(d.base_salary+p.position_salary) AS basic_salary FROM position_info p JOIN department d ON d.id=p.department_id WHERE p.status=1 ORDER BY d.id,p.id");
    }
    public List<Map<String,Object>> positionsByDepartment(Long id) {
        return jdbc.queryForList("SELECT * FROM position_info WHERE department_id=? AND status=1 ORDER BY is_manager_position,position_name", id);
    }
    public List<Map<String,Object>> listManagerCandidates() {
        return jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,e.department_id,e.user_type,p.position_name
            FROM employee e
            LEFT JOIN position_info p ON p.id=e.position_id
            JOIN department d ON d.id=e.department_id
            WHERE e.employment_status=1 AND e.user_type IN(0,1) AND d.status<>2
            ORDER BY e.department_id,e.user_type DESC,e.employee_no
            """);
    }
    @Transactional
    public void createDepartment(String name, BigDecimal salary, String description, Long operatorId) {
        guard(operatorId,2);
        requireText(name,"部门名称不能为空"); requireMoney(salary,"基础工资不能为负数");
        jdbc.update("INSERT INTO department(department_name,base_salary,status,description) VALUES(?,?,0,?)",name.trim(),salary,description);
        Long id=jdbc.queryForObject("SELECT id FROM department WHERE department_name=?",Long.class,name.trim());
        logService.log(operatorId,"CREATE_DEPARTMENT","DEPARTMENT",id,"创建部门："+name.trim());
    }
    @Transactional
    public void createPosition(Long departmentId,String name,BigDecimal salary,boolean managerPosition,Long operatorId) {
        guard(operatorId,2);
        requireText(name,"职位名称不能为空"); requireMoney(salary,"岗位工资不能为负数");
        if(managerPosition) throw new IllegalArgumentException("经理职位由系统自动维护，不能手动创建");
        jdbc.update("INSERT INTO position_info(department_id,position_name,position_salary,is_manager_position) VALUES(?,?,?,0)",departmentId,name.trim(),salary);
        logService.log(operatorId,"CREATE_POSITION","POSITION",null,"创建职位："+name.trim());
    }
    @Transactional
    public void updateDepartment(Long id,String name,BigDecimal salary,String description,Long operatorId) {
        guard(operatorId,2);
        requireText(name,"部门名称不能为空"); requireMoney(salary,"基础工资不能为负数");
        Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE department_name=? AND id<>?",Integer.class,name.trim(),id);
        if(duplicate!=null&&duplicate>0) throw new IllegalArgumentException("部门名称已经存在");
        if(jdbc.update("UPDATE department SET department_name=?,base_salary=?,description=? WHERE id=?",name.trim(),salary,description,id)==0) throw new IllegalArgumentException("部门不存在");
        logService.log(operatorId,"UPDATE_DEPARTMENT","DEPARTMENT",id,"修改部门："+name.trim());
    }
    @Transactional
    public void deleteDepartment(Long id,Long operatorId) {
        guard(operatorId,2);
        Map<String,Object> d=getDepartment(id);
        Long managerId=d.get("manager_id")==null?null:((Number)d.get("manager_id")).longValue();
        Integer otherActive;
        if(managerId==null){
            otherActive=jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE department_id=? AND employment_status IN(0,1,2)",Integer.class,id);
        }else{
            otherActive=jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE department_id=? AND id<>? AND employment_status IN(0,1,2)",Integer.class,id,managerId);
        }
        if(otherActive!=null&&otherActive>0) throw new IllegalArgumentException("该部门仍有其他员工，请先将除经理外的员工调往其他部门");
        if(managerId!=null){
            Map<String,Object> manager=jdbc.queryForMap("SELECT position_id FROM employee WHERE id=?",managerId);
            Long oldPositionId=manager.get("position_id")==null?null:((Number)manager.get("position_id")).longValue();
            jdbc.update("UPDATE employee SET user_type=0,department_id=NULL,position_id=NULL WHERE id=?",managerId);
            jdbc.update("INSERT INTO employee_change_record(employee_id,change_type,old_department_id,new_department_id,old_position_id,new_position_id,reason,operator_id) VALUES(?,?,?,?,?,?,?,?)",
                    managerId,"DEPARTMENT_DISSOLUTION",id,null,oldPositionId,null,"部门解散，原经理转为未分配普通员工",operatorId);
        }
        jdbc.update("UPDATE department SET status=2,manager_id=NULL WHERE id=?",id);
        jdbc.update("UPDATE position_info SET status=0 WHERE department_id=?",id);
        logService.log(operatorId,"DISSOLVE_DEPARTMENT","DEPARTMENT",id,"解散部门："+d.get("department_name")+"；原经理已撤职并转为未分配员工");
    }
    @Transactional
    public void updatePosition(Long id,String name,BigDecimal salary,Long operatorId) {
        guard(operatorId,2);
        requireText(name,"职位名称不能为空"); requireMoney(salary,"岗位工资不能为负数");
        Map<String,Object> p=getPosition(id); Long departmentId=((Number)p.get("department_id")).longValue();
        if(((Number)p.get("is_manager_position")).intValue()==1&&!"部门经理".equals(name.trim())) throw new IllegalArgumentException("经理职位名称固定为“部门经理”");
        Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM position_info WHERE department_id=? AND position_name=? AND id<>?",Integer.class,departmentId,name.trim(),id);
        if(duplicate!=null&&duplicate>0) throw new IllegalArgumentException("该部门已经存在同名职位");
        jdbc.update("UPDATE position_info SET position_name=?,position_salary=? WHERE id=?",name.trim(),salary,id);
        logService.log(operatorId,"UPDATE_POSITION","POSITION",id,"修改职位："+name.trim());
    }
    @Transactional
    public void deletePosition(Long id,Long operatorId) {
        guard(operatorId,2);
        Map<String,Object> p=getPosition(id);
        if(((Number)p.get("is_manager_position")).intValue()==1) throw new IllegalArgumentException("部门经理职位不能删除");
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE position_id=?",Integer.class,id);
        if(count!=null&&count>0) throw new IllegalArgumentException("仍有员工使用该职位，不能删除");
        jdbc.update("DELETE FROM position_info WHERE id=?",id);
        logService.log(operatorId,"DELETE_POSITION","POSITION",id,"删除职位："+p.get("position_name"));
    }
    @Transactional
    public void appointManagerByEmployeeNo(Long departmentId,String employeeNo,Long operatorId) {
        guard(operatorId,2);
        if(employeeNo==null||!employeeNo.matches("^\\d{6}$")) throw new IllegalArgumentException("请输入正确的六位工号");
        Map<String,Object> e;
        try{e=jdbc.queryForMap("SELECT id,name,department_id,employment_status,user_type FROM employee WHERE employee_no=?",employeeNo);}
        catch(EmptyResultDataAccessException ex){throw new IllegalArgumentException("工号不存在："+employeeNo);}
        if(((Number)e.get("user_type")).intValue()==2)throw new IllegalArgumentException("不能通过经理任免更改高层身份");
        Long employeeId=((Number)e.get("id")).longValue();
        if(((Number)e.get("employment_status")).intValue()!=1) throw new IllegalArgumentException("只能任命在职员工");
        if(e.get("department_id")==null||!departmentId.equals(((Number)e.get("department_id")).longValue())) throw new IllegalArgumentException("只能任命本部门员工为经理");
        Long oldManager=jdbc.query("SELECT manager_id FROM department WHERE id=?",rs->rs.next()?(Long)rs.getObject(1):null,departmentId);
        Long ordinaryPosition=jdbc.query("SELECT id FROM position_info WHERE department_id=? AND is_manager_position=0 AND status=1 ORDER BY id LIMIT 1",rs->rs.next()?rs.getLong(1):null,departmentId);
        if(oldManager!=null&&!oldManager.equals(employeeId)){
            if(ordinaryPosition==null) throw new IllegalArgumentException("请先为原经理创建一个普通职位");
            jdbc.update("UPDATE employee SET user_type=0,position_id=? WHERE id=?",ordinaryPosition,oldManager);
        }
        Long managerPosition=jdbc.query("SELECT id FROM position_info WHERE department_id=? AND is_manager_position=1 AND status=1 LIMIT 1",rs->rs.next()?rs.getLong(1):null,departmentId);
        if(managerPosition==null){
            jdbc.update("INSERT INTO position_info(department_id,position_name,position_salary,is_manager_position) VALUES(?,'部门经理',3000,1)",departmentId);
            managerPosition=jdbc.queryForObject("SELECT id FROM position_info WHERE department_id=? AND is_manager_position=1 LIMIT 1",Long.class,departmentId);
        }
        jdbc.update("UPDATE employee SET user_type=1,position_id=? WHERE id=?",managerPosition,employeeId);
        // Promotion changes the approver class of the employee's own pending requests.
        Long executive=jdbc.query("SELECT e.id FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.user_type=2 AND e.employment_status=1 AND a.account_status=1 ORDER BY e.id LIMIT 1",rs->rs.next()?rs.getLong(1):null);
        var ownPending=jdbc.queryForList("SELECT id FROM leave_request WHERE employee_id=? AND status=1 FOR UPDATE",employeeId);
        if(!ownPending.isEmpty()&&executive==null)throw new IllegalArgumentException("缺少可审批的高层，不能转交新经理的待审批申请");
        for(var pending:ownPending){
            jdbc.update("UPDATE leave_request SET current_approver_id=? WHERE id=? AND status=1",executive,pending.get("id"));
            jdbc.update("INSERT INTO leave_approval_record(leave_request_id,approver_id,action,before_status,after_status,opinion) VALUES(?,?,'TRANSFER',1,1,'经理任命后自动转交高层')",pending.get("id"),operatorId);
        }
        jdbc.update("UPDATE department SET manager_id=?,status=1 WHERE id=?",employeeId,departmentId);
        if(oldManager!=null&&!oldManager.equals(employeeId)){
            jdbc.update("""
                UPDATE leave_request l JOIN employee e ON e.id=l.employee_id
                SET l.current_approver_id=?
                WHERE l.status=1 AND e.department_id=? AND e.user_type=0 AND l.current_approver_id=?
                """,employeeId,departmentId,oldManager);
        }
        logService.log(operatorId,"APPOINT_MANAGER","DEPARTMENT",departmentId,"任命工号 "+employeeNo+" 为经理");
    }
    private Map<String,Object> getDepartment(Long id){try{return jdbc.queryForMap("SELECT * FROM department WHERE id=?",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("部门不存在");}}
    private Map<String,Object> getPosition(Long id){try{return jdbc.queryForMap("SELECT * FROM position_info WHERE id=?",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("职位不存在");}}
    private void requireText(String v,String m){if(v==null||v.isBlank())throw new IllegalArgumentException(m);}
    private void requireMoney(BigDecimal v,String m){if(v==null||v.signum()<0)throw new IllegalArgumentException(m);}
}
