package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.DepartmentService;
import com.example.ems.service.EmployeeService;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private final JdbcTemplate jdbc; private final EmployeeService employees; private final DepartmentService departments;
    public HomeController(JdbcTemplate jdbc,EmployeeService employees,DepartmentService departments){this.jdbc=jdbc;this.employees=employees;this.departments=departments;}
    @GetMapping("/dashboard") public String dashboard(HttpSession session,Model model){
        SessionUser u=(SessionUser)session.getAttribute("user");
        model.addAttribute("employeeCount",u.isExecutive()?jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE employment_status=1",Integer.class):jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE employment_status=1 AND department_id=?",Integer.class,u.departmentId()));
        model.addAttribute("departmentCount",jdbc.queryForObject("SELECT COUNT(*) FROM department WHERE status<>2",Integer.class));
        model.addAttribute("pendingCount",u.isExecutive()?jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE employment_status=0",Integer.class):0);
        model.addAttribute("departments",departments.listDepartments()); return "dashboard";
    }
    @GetMapping("/profile") public String profile(HttpSession session,Model model){SessionUser u=(SessionUser)session.getAttribute("user");model.addAttribute("employee",employees.detail(u.employeeNo(),u));return "profile";}
}
