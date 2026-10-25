package com.example.ems.service;

import com.example.ems.common.*;
import com.example.ems.model.SessionUser;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EmployeeService {
    private final JdbcTemplate jdbc;
    private final LogService logService;
    public EmployeeService(JdbcTemplate jdbc,LogService logService){this.jdbc=jdbc;this.logService=logService;}

    /**
     * 分页查询员工。总数与列表共用同一组条件（含部门权限），页码越界时修正到末页。
     * 非高层只能看到本部门，传入的部门条件被忽略；工资合计只返回给高层。
     */
    public PageResult page(SessionUser user, EmployeeListQuery query){
        StringBuilder where=new StringBuilder(" WHERE 1=1");
        ArrayList<Object> args=new ArrayList<>();
        if(!user.isExecutive()){
            if(user.departmentId()==null) where.append(" AND 1=0");
            else {where.append(" AND e.department_id=?");args.add(user.departmentId());}
        } else if(query.getDepartmentId()!=null){where.append(" AND e.department_id=?");args.add(query.getDepartmentId());}
        if(query.getKeyword()!=null){
            where.append(" AND (e.name LIKE ? OR e.employee_no LIKE ?)");
            String like=SqlLike.contains(query.getKeyword());args.add(like);args.add(like);
        }
        if(query.getStatus()!=null){where.append(" AND e.employment_status=?");args.add(query.getStatus());}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM employee e"+where,Long.class,args.toArray());
        Pagination pagination=Pagination.of(query.getPage(),query.getPageSize(),total==null?0:total);
        ArrayList<Object> pageArgs=new ArrayList<>(args);pageArgs.add(pagination.getPageSize());pageArgs.add(pagination.getOffset());
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,e.birth_date,e.phone,e.user_type,e.hire_date,e.employment_status,
                   d.department_name,p.position_name,(d.base_salary+p.position_salary) basic_salary,e.department_id,e.position_id
            FROM employee e LEFT JOIN department d ON d.id=e.department_id LEFT JOIN position_info p ON p.id=e.position_id
            """+where+" ORDER BY e.id LIMIT ? OFFSET ?",pageArgs.toArray());
        if(!user.isExecutive()) rows.forEach(r->r.remove("basic_salary"));
        return new PageResult(rows,pagination);
    }

    public Map<String,Object> detail(String employeeNo,SessionUser user){
        Map<String,Object> row=getEmployee(employeeNo); Long id=((Number)row.get("id")).longValue();
        if(!user.isExecutive()&&!user.id().equals(id)) throw new ForbiddenException("员工个人档案仅限本人或高层查看");
        row.put("masked_identity",maskIdentity(String.valueOf(row.get("identity_number"))));row.remove("identity_number");return row;
    }

    @Transactional
    public String recruit(String name,String identityNumber,LocalDate birthDate,LocalDate hireDate,Long departmentId,Long positionId,int userType,Long operatorId){
        if(name==null||name.isBlank())throw new IllegalArgumentException("姓名不能为空");
        if(identityNumber==null||!identityNumber.matches("^[0-9]{17}[0-9Xx]$"))throw new IllegalArgumentException("身份证号格式不正确");
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE identity_number=?",Integer.class,identityNumber);
        if(count!=null&&count>0)throw new IllegalArgumentException("身份证号已经存在");
        if(positionId!=null) validatePosition(departmentId,positionId,false);
        else if(departmentId!=null){Integer valid=jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE id=? AND status<>2",Integer.class,departmentId);if(valid==null||valid==0)throw new IllegalArgumentException("所选部门不存在或已经停用");}
        String no=nextEmployeeNo();jdbc.update("INSERT INTO employee(employee_no,name,identity_number,birth_date,department_id,position_id,user_type,hire_date,employment_status) VALUES(?,?,?,?,?,?,?,?,0)",no,name.trim(),identityNumber.toUpperCase(),birthDate,departmentId,positionId,userType,hireDate);
        Long id=jdbc.queryForObject("SELECT id FROM employee WHERE employee_no=?",Long.class,no);logService.log(operatorId,"RECRUIT","EMPLOYEE",id,"招聘员工"+name+"，工号"+no);return no;
    }

    @Transactional
    public void changeDepartment(String employeeNo,Long newDepartmentId,String reason,SessionUser operator){
        if(!operator.isExecutive())throw new IllegalArgumentException("只有高层可以跨部门调动员工");
        Map<String,Object> e=getEmployee(employeeNo);Long id=((Number)e.get("id")).longValue();int type=((Number)e.get("user_type")).intValue();
        if(((Number)e.get("employment_status")).intValue()!=1)throw new IllegalArgumentException("只能调动在职员工");
        if(type==1)throw new IllegalArgumentException("经理必须先完成经理更换，才能调往其他部门");
        if(type==2)throw new IllegalArgumentException("不能调动高层账户");
        Integer valid=jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE id=? AND status<>2",Integer.class,newDepartmentId);
        if(valid==null||valid==0)throw new IllegalArgumentException("目标部门不存在或已经停用");
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM leave_request WHERE employee_id=? AND status=1",Integer.class,id);
        Long targetManager=jdbc.query("SELECT manager_id FROM department WHERE id=?",rs->rs.next()?(Long)rs.getObject(1):null,newDepartmentId);
        if(pending!=null&&pending>0&&targetManager==null)throw new IllegalArgumentException("该员工有待审批请假，目标部门尚未任命经理，暂时不能调动");
        Long oldDept=e.get("department_id")==null?null:((Number)e.get("department_id")).longValue();Long oldPos=e.get("position_id")==null?null:((Number)e.get("position_id")).longValue();
        if(newDepartmentId.equals(oldDept))throw new IllegalArgumentException("员工已经属于该部门");
        jdbc.update("UPDATE employee SET department_id=?,position_id=NULL WHERE id=?",newDepartmentId,id);
        if(pending!=null&&pending>0) jdbc.update("UPDATE leave_request SET current_approver_id=? WHERE employee_id=? AND status=1",targetManager,id);
        recordChange(id,"DEPARTMENT_TRANSFER",oldDept,newDepartmentId,oldPos,null,reason,operator.id());
        logService.log(operator.id(),"DEPARTMENT_TRANSFER","EMPLOYEE",id,"调动工号 "+employeeNo+" 的部门："+reason);
    }

    @Transactional
    public void changePosition(String employeeNo,Long newPositionId,String reason,SessionUser operator){
        if(!operator.isManager())throw new IllegalArgumentException("只有部门经理可以调整员工职位");
        Map<String,Object> e=getEmployee(employeeNo);Long id=((Number)e.get("id")).longValue();
        if(((Number)e.get("employment_status")).intValue()!=1)throw new IllegalArgumentException("只能调整在职员工职位");
        if(((Number)e.get("user_type")).intValue()!=0)throw new IllegalArgumentException("经理只能调整普通员工职位");
        Long dept=e.get("department_id")==null?null:((Number)e.get("department_id")).longValue();
        if(operator.departmentId()==null||!operator.departmentId().equals(dept))throw new IllegalArgumentException("只能调整自己部门员工的职位");
        validatePosition(dept,newPositionId,false);Long oldPos=e.get("position_id")==null?null:((Number)e.get("position_id")).longValue();
        jdbc.update("UPDATE employee SET position_id=? WHERE id=? AND department_id=? AND user_type=0 AND employment_status=1",newPositionId,id,dept);
        recordChange(id,"POSITION_CHANGE",dept,dept,oldPos,newPositionId,reason,operator.id());
        logService.log(operator.id(),"POSITION_CHANGE","EMPLOYEE",id,"调整工号 "+employeeNo+" 的职位："+reason);
    }

    @Transactional
    public void terminate(String employeeNo,String reason,SessionUser operator){
        if(!operator.isExecutive())throw new IllegalArgumentException("只有高层可以办理离职");
        Map<String,Object> e=getEmployee(employeeNo);Long id=((Number)e.get("id")).longValue();
        if(((Number)e.get("employment_status")).intValue()==3)throw new IllegalArgumentException("员工已经离职");
        if(((Number)e.get("user_type")).intValue()==1)throw new IllegalArgumentException("请先更换部门经理再办理离职");
        if(((Number)e.get("user_type")).intValue()==2)throw new IllegalArgumentException("不能通过员工离职功能停用高层");
        jdbc.update("UPDATE employee SET employment_status=3,leave_date=CURDATE() WHERE id=?",id);jdbc.update("UPDATE user_account SET account_status=0 WHERE employee_id=?",id);
        logService.log(operator.id(),"TERMINATE","EMPLOYEE",id,"工号 "+employeeNo+" 离职："+reason);
    }

    public List<Map<String,Object>> profileOptions(){return jdbc.queryForList("SELECT dict_type,item_value,item_label FROM dictionary_item WHERE status=1 ORDER BY dict_type,sort_order");}
    private Map<String,Object> getEmployee(String no){
        try{return jdbc.queryForMap("SELECT e.*,d.department_name,p.position_name,d.base_salary,p.position_salary,(d.base_salary+p.position_salary) basic_salary FROM employee e LEFT JOIN department d ON d.id=e.department_id LEFT JOIN position_info p ON p.id=e.position_id WHERE e.employee_no=?",no);}
        catch(EmptyResultDataAccessException ex){throw new NotFoundException("工号不存在："+no);}
    }
    private void validatePosition(Long departmentId,Long positionId,boolean allowManager){
        if(departmentId==null&&positionId==null)return;if(departmentId==null||positionId==null)throw new IllegalArgumentException("部门和职位必须同时选择");
        Map<String,Object> p;try{p=jdbc.queryForMap("SELECT department_id,is_manager_position,status FROM position_info WHERE id=?",positionId);}catch(EmptyResultDataAccessException ex){throw new IllegalArgumentException("职位不存在");}
        if(!departmentId.equals(((Number)p.get("department_id")).longValue()))throw new IllegalArgumentException("职位不属于所选部门");
        if(((Number)p.get("status")).intValue()!=1)throw new IllegalArgumentException("职位已经停用");
        if(!allowManager&&((Number)p.get("is_manager_position")).intValue()==1)throw new IllegalArgumentException("经理职位只能由高层通过经理任免功能任命");
    }
    private void recordChange(Long id,String type,Long oldDept,Long newDept,Long oldPos,Long newPos,String reason,Long operator){jdbc.update("INSERT INTO employee_change_record(employee_id,change_type,old_department_id,new_department_id,old_position_id,new_position_id,reason,operator_id) VALUES(?,?,?,?,?,?,?,?)",id,type,oldDept,newDept,oldPos,newPos,reason,operator);}
    private String nextEmployeeNo(){String prefix=String.valueOf(Year.now().getValue()).substring(2);String max=jdbc.query("SELECT MAX(employee_no) FROM employee WHERE employee_no LIKE ?",rs->rs.next()?rs.getString(1):null,prefix+"%");int seq=max==null?1:Integer.parseInt(max.substring(2))+1;if(seq>9999)throw new IllegalStateException("本年度工号已达到上限");return prefix+String.format("%04d",seq);}
    private String maskIdentity(String v){if(v==null||v.length()<8)return "****";return v.substring(0,3)+"***********"+v.substring(v.length()-4);}
}
