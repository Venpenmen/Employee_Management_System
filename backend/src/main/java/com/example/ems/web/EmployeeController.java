package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.DepartmentService;
import com.example.ems.service.EmployeeService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller @RequestMapping("/employees")
public class EmployeeController {
    private final EmployeeService service; private final DepartmentService departments;
    public EmployeeController(EmployeeService service,DepartmentService departments){this.service=service;this.departments=departments;}

    @GetMapping
    public String list(@RequestParam(required=false)String keyword,@RequestParam(required=false)Long departmentId,@RequestParam(required=false)Integer status,HttpSession session,Model model){
        SessionUser u=user(session);model.addAttribute("employees",service.list(u,keyword,departmentId,status));
        List<Map<String,Object>> departmentOptions=u.isExecutive()?departments.listDepartments():departments.listDepartments().stream().filter(d->u.departmentId()!=null&&u.departmentId().equals(((Number)d.get("id")).longValue())).toList();
        List<Map<String,Object>> positionOptions=u.isManager()?departments.positionsByDepartment(u.departmentId()).stream().filter(p->((Number)p.get("is_manager_position")).intValue()==0).toList():List.of();
        model.addAttribute("departments",departmentOptions);model.addAttribute("positions",positionOptions);model.addAttribute("keyword",keyword);return "employees";
    }
    @GetMapping("/{employeeNo}") public String detail(@PathVariable String employeeNo,HttpSession s,Model m,RedirectAttributes ra){try{m.addAttribute("employee",service.detail(employeeNo,user(s)));return "employee-detail";}catch(Exception e){ra.addFlashAttribute("error",e.getMessage());return "redirect:/employees";}}
    @PostMapping("/recruit") public String recruit(@RequestParam String name,@RequestParam String identityNumber,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate birthDate,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hireDate,@RequestParam(required=false)Long departmentId,@RequestParam(required=false)Long positionId,HttpSession s,RedirectAttributes ra){
        SessionUser u=user(s);try{if(!u.isExecutive())throw new IllegalArgumentException("只有高层可以招聘员工");String no=service.recruit(name,identityNumber,birthDate,hireDate,departmentId,positionId,0,u.id());ra.addFlashAttribute("success","招聘成功，新员工工号："+no);}catch(Exception e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/employees";
    }
    @PostMapping("/{employeeNo}/department") public String department(@PathVariable String employeeNo,@RequestParam Long departmentId,@RequestParam String reason,HttpSession s,RedirectAttributes ra){
        try{service.changeDepartment(employeeNo,departmentId,reason,user(s));ra.addFlashAttribute("success","员工已调入新部门，等待新部门经理分配职位");}catch(Exception e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/employees";
    }
    @PostMapping("/{employeeNo}/position") public String position(@PathVariable String employeeNo,@RequestParam Long positionId,@RequestParam String reason,HttpSession s,RedirectAttributes ra){
        try{service.changePosition(employeeNo,positionId,reason,user(s));ra.addFlashAttribute("success","员工职位调整成功");}catch(Exception e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/employees";
    }
    @PostMapping("/{employeeNo}/terminate") public String terminate(@PathVariable String employeeNo,@RequestParam String reason,HttpSession s,RedirectAttributes ra){
        try{service.terminate(employeeNo,reason,user(s));ra.addFlashAttribute("success","离职处理完成");}catch(Exception e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/employees";
    }
    private SessionUser user(HttpSession s){return (SessionUser)s.getAttribute("user");}
}
