package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@Controller @RequestMapping("/admin")
public class AdminController {
    private final DepartmentService departments;private final EmployeeService employees;private final SalaryService salaries;private final ExcelService excel;private final JdbcTemplate jdbc;
    public AdminController(DepartmentService d,EmployeeService e,SalaryService s,ExcelService x,JdbcTemplate j){departments=d;employees=e;salaries=s;excel=x;jdbc=j;}
    @GetMapping("/departments") public String departments(Model m){m.addAttribute("departments",departments.listDepartments());m.addAttribute("managerCandidates",departments.listManagerCandidates());return "departments";}
    @GetMapping("/departments/new") public String newDepartment(){return "department-new";}
    @PostMapping("/departments") public String createDepartment(@RequestParam String name,@RequestParam BigDecimal baseSalary,@RequestParam(required=false)String description,HttpSession s,org.springframework.web.servlet.mvc.support.RedirectAttributes ra){try{departments.createDepartment(name,baseSalary,description,user(s).id());ra.addFlashAttribute("success","部门创建成功");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/departments/new";}
    @GetMapping("/positions/new") public String newPosition(Model m){m.addAttribute("departments",departments.listDepartments());return "position-new";}
    @PostMapping("/positions") public String createPosition(@RequestParam Long departmentId,@RequestParam String name,@RequestParam BigDecimal salary,@RequestParam(defaultValue="false")boolean managerPosition,HttpSession s,org.springframework.web.servlet.mvc.support.RedirectAttributes ra){try{departments.createPosition(departmentId,name,salary,managerPosition,user(s).id());ra.addFlashAttribute("success","职位创建成功");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/positions/new";}
    @GetMapping("/positions/manage") public String positions(Model m){m.addAttribute("positions",departments.listPositions());return "positions";}
    @PostMapping("/departments/{id}/manager") public String manager(@PathVariable Long id,@RequestParam String employeeNo,HttpSession s,RedirectAttributes ra){try{departments.appointManagerByEmployeeNo(id,employeeNo,user(s).id());ra.addFlashAttribute("success","经理任命完成，新经理工号："+employeeNo);}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/departments";}
    @PostMapping("/departments/{id}/update") public String updateDepartment(@PathVariable Long id,@RequestParam String departmentName,@RequestParam BigDecimal baseSalary,@RequestParam(required=false)String description,HttpSession s,RedirectAttributes ra){try{departments.updateDepartment(id,departmentName,baseSalary,description,user(s).id());ra.addFlashAttribute("success","部门信息修改成功");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/departments";}
    @PostMapping("/departments/{id}/delete") public String deleteDepartment(@PathVariable Long id,HttpSession s,RedirectAttributes ra){try{departments.deleteDepartment(id,user(s).id());ra.addFlashAttribute("success","部门删除或停用处理完成");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/departments";}
    @PostMapping("/positions/{id}/update") public String updatePosition(@PathVariable Long id,@RequestParam String positionName,@RequestParam BigDecimal positionSalary,HttpSession s,RedirectAttributes ra){try{departments.updatePosition(id,positionName,positionSalary,user(s).id());ra.addFlashAttribute("success","职位修改成功");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/positions/manage";}
    @PostMapping("/positions/{id}/delete") public String deletePosition(@PathVariable Long id,HttpSession s,RedirectAttributes ra){try{departments.deletePosition(id,user(s).id());ra.addFlashAttribute("success","职位删除成功");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/positions/manage";}
    @GetMapping("/salaries") public String salaries(@RequestParam(required=false)String month,Model m,HttpSession s){m.addAttribute("records",salaries.list(month,user(s)));m.addAttribute("month",month);return "salaries";}
    @PostMapping("/salaries/settle") public String settle(@RequestParam String month,HttpSession s,org.springframework.web.servlet.mvc.support.RedirectAttributes ra){try{int n=salaries.settle(month,user(s).id());ra.addFlashAttribute("success","月结完成，共生成"+n+"张工资条");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return salariesPage(month);}
    @PostMapping("/salaries/{id}/adjust") public String adjust(@PathVariable Long id,@RequestParam BigDecimal reward,@RequestParam BigDecimal subsidy,@RequestParam BigDecimal overtimePay,@RequestParam BigDecimal otherIncome,@RequestParam BigDecimal deduction,@RequestParam BigDecimal otherDeduction,@RequestParam String month,HttpSession s,org.springframework.web.servlet.mvc.support.RedirectAttributes ra){try{salaries.adjust(id,reward,subsidy,overtimePay,otherIncome,deduction,otherDeduction,user(s).id());ra.addFlashAttribute("success","工资项目已更新");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return salariesPage(month);}
    @PostMapping("/salaries/publish") public String publish(@RequestParam String month,HttpSession s,org.springframework.web.servlet.mvc.support.RedirectAttributes ra){salaries.publishMonth(month,user(s).id());ra.addFlashAttribute("success","工资条已发布");return salariesPage(month);}
    @GetMapping("/salaries/export") public void export(@RequestParam String month,HttpServletResponse response)throws Exception{if(!month.matches("^\\d{4}-(0[1-9]|1[0-2])$"))throw new IllegalArgumentException("工资月份格式应为YYYY-MM");response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode(month+"工资表.xlsx", StandardCharsets.UTF_8));response.getOutputStream().write(excel.exportSalaries(month));}
    @GetMapping("/recruitment") public String recruitment(Model m){m.addAttribute("departments",departments.listDepartments());return "recruitment";}
    @PostMapping("/recruitment/single") public String recruit(@RequestParam String name,@RequestParam String identityNumber,@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate birthDate,@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate hireDate,@RequestParam(required=false)Long departmentId,HttpSession s,RedirectAttributes ra){try{String no=employees.recruit(name,identityNumber,birthDate,hireDate,departmentId,null,0,user(s).id());ra.addFlashAttribute("success","招聘成功，新员工工号："+no);}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/recruitment";}
    @PostMapping(value="/recruitment/import",consumes= MediaType.MULTIPART_FORM_DATA_VALUE) public String importExcel(@RequestParam MultipartFile file,HttpSession s,Model m){m.addAttribute("departments",departments.listDepartments());try{m.addAttribute("result",excel.importEmployees(file,user(s).id()));}catch(IllegalArgumentException e){m.addAttribute("error",e.getMessage());}return "recruitment";}
    @GetMapping("/import") public String oldImport(){return "redirect:/admin/recruitment";}
    @GetMapping("/logs") public String logs(Model m){m.addAttribute("logs",jdbc.queryForList("SELECT l.*,e.name operator_name,e.employee_no FROM operation_log l LEFT JOIN employee e ON e.id=l.operator_id ORDER BY l.id DESC LIMIT 200"));return "logs";}
    /** 只把格式正确的月份带回列表地址，避免把任意输入拼进重定向。 */
    private String salariesPage(String month){return month!=null&&month.matches("^\\d{4}-(0[1-9]|1[0-2])$")?"redirect:/admin/salaries?month="+month:"redirect:/admin/salaries";}
    private SessionUser user(HttpSession s){return (SessionUser)s.getAttribute("user");}
}
