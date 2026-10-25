package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.DepartmentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    /** 普通员工和经理的部门、职位选项中不返回的工资字段。 */
    static final Set<String> SALARY_FIELDS = Set.of("base_salary", "position_salary");

    private final JdbcTemplate jdbc;
    private final DepartmentService departments;
    public ApiController(JdbcTemplate jdbc, DepartmentService departments){this.jdbc=jdbc;this.departments=departments;}

    @GetMapping("/dictionaries/{type}")
    public Map<String,Object> dictionary(@PathVariable String type){
        return ok(jdbc.queryForList("SELECT item_value value,item_label label FROM dictionary_item WHERE dict_type=? AND status=1 ORDER BY sort_order",type));
    }

    @GetMapping("/dictionaries/employee-options")
    public Map<String,Object> employeeOptions(HttpSession session){
        SessionUser u=user(session);
        Map<String,Object> data=new LinkedHashMap<>();
        for(String type:List.of("gender","marriage","political","nation"))
            data.put(type,jdbc.queryForList("SELECT item_value value,item_label label FROM dictionary_item WHERE dict_type=? AND status=1 ORDER BY sort_order",type));
        data.put("departments",project(departments.listDepartments(),u));
        data.put("positions",project(departments.listPositions(),u));
        return ok(data);
    }

    @GetMapping("/departments/{id}/positions")
    public Map<String,Object> positions(@PathVariable Long id,HttpSession session){return ok(project(departments.positionsByDepartment(id),user(session)));}

    @GetMapping("/me")
    public Map<String,Object> me(HttpSession session){return ok(user(session));}

    /** 工资字段只返回给系统高层。 */
    static List<Map<String,Object>> project(List<Map<String,Object>> rows,SessionUser u){
        if(u!=null&&u.isExecutive()) return rows;
        List<Map<String,Object>> result=new ArrayList<>(rows.size());
        for(Map<String,Object> row:rows){
            Map<String,Object> copy=new LinkedHashMap<>(row);
            SALARY_FIELDS.forEach(copy::remove);
            result.add(copy);
        }
        return result;
    }

    private SessionUser user(HttpSession session){return (SessionUser)session.getAttribute("user");}
    private Map<String,Object> ok(Object data){
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("code",200);body.put("msg","查询成功");body.put("data",data);
        return body;
    }
}
