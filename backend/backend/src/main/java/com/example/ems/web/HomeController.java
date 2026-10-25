package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.DepartmentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
public class HomeController {
    private final JdbcTemplate jdbc; private final DepartmentService departments;
    public HomeController(JdbcTemplate jdbc,DepartmentService departments){this.jdbc=jdbc;this.departments=departments;}

    @GetMapping("/dashboard") public String dashboard(HttpSession session,Model model){
        SessionUser u=(SessionUser)session.getAttribute("user");
        model.addAttribute("employeeCount",u.isExecutive()
                ?jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE employment_status=1",Integer.class)
                :(u.departmentId()==null?0:jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE employment_status=1 AND department_id=?",Integer.class,u.departmentId())));
        model.addAttribute("departmentCount",jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE status<>2",Integer.class));
        model.addAttribute("pendingCount",u.isExecutive()?jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE employment_status=0",Integer.class):0);
        // 只统计当前登录人作为审批人负责的待处理请假
        model.addAttribute("approverPendingCount",u.isManager()||u.isExecutive()
                ?jdbc.queryForObject("SELECT COUNT(*) FROM leave_request WHERE current_approver_id=? AND status=1",Integer.class,u.id()):0);
        List<Map<String,Object>> list=departments.listDepartments();
        if(!u.isExecutive()) list.forEach(d->d.remove("base_salary")); // 部门基础工资仅高层可见
        model.addAttribute("departments",list);
        return "dashboard";
    }
}
