package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.DepartmentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc;
    private final DepartmentService departments;
    public ApiController(JdbcTemplate jdbc, DepartmentService departments){this.jdbc=jdbc;this.departments=departments;}

    @GetMapping("/dictionaries/{type}")
    public Map<String,Object> dictionary(@PathVariable String type){
        return ok(jdbc.queryForList("SELECT item_value value,item_label label FROM dictionary_item WHERE dict_type=? AND status=1 ORDER BY sort_order",type));
    }

    @GetMapping("/dictionaries/employee-options")
    public Map<String,Object> employeeOptions(HttpSession session){
        boolean executive=((SessionUser)session.getAttribute("user")).isExecutive();
        Map<String,Object> data=new LinkedHashMap<>();
        for(String type:List.of("gender","marriage","political","nation"))
            data.put(type,jdbc.queryForList("SELECT item_value value,item_label label FROM dictionary_item WHERE dict_type=? AND status=1 ORDER BY sort_order",type));
        data.put("departments",OptionViews.departments(departments.listDepartments(),executive));
        data.put("positions",OptionViews.positions(departments.listPositions(),executive));
        return ok(data);
    }

    @GetMapping("/departments/{id}/positions")
    public Map<String,Object> positions(@PathVariable Long id,HttpSession session){return ok(OptionViews.positions(departments.positionsByDepartment(id),((SessionUser)session.getAttribute("user")).isExecutive()));}

    @GetMapping("/me")
    public Map<String,Object> me(HttpSession session){return ok((SessionUser)session.getAttribute("user"));}

    private Map<String,Object> ok(Object data){return Map.of("code",200,"msg","查询成功","data",data);}
}
