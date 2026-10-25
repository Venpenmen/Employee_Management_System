package com.example.ems.web;

import com.example.ems.common.EmployeeListQuery;
import com.example.ems.common.ForbiddenException;
import com.example.ems.common.PageResult;
import com.example.ems.model.SessionUser;
import com.example.ems.profile.ProfileService;
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
    private final EmployeeService service; private final DepartmentService departments; private final ProfileService profiles;
    public EmployeeController(EmployeeService service,DepartmentService departments,ProfileService profiles){this.service=service;this.departments=departments;this.profiles=profiles;}

    /** 员工列表：分页 20/50/100 条，筛选条件保留；页码、状态等非数字参数由框架返回 400。 */
    @GetMapping
    public String list(@RequestParam(required=false)String keyword,@RequestParam(required=false)Long departmentId,@RequestParam(required=false)Integer status,
                       @RequestParam(required=false)Integer page,@RequestParam(required=false)Integer pageSize,HttpSession session,Model model){
        SessionUser u=user(session);
        EmployeeListQuery query=new EmployeeListQuery(keyword,departmentId,status,page,pageSize);
        if(!u.isExecutive()) query=query.withoutDepartment(); // 伪造的部门条件不能越权
        PageResult result=service.page(u,query);
        query=query.withPage(result.pagination().getPage());

        List<Map<String,Object>> departmentOptions=u.isExecutive()?departments.listDepartments():departments.listDepartments().stream().filter(d->u.departmentId()!=null&&u.departmentId().equals(((Number)d.get("id")).longValue())).toList();
        if(!u.isExecutive()) departmentOptions.forEach(d->d.remove("base_salary"));
        List<Map<String,Object>> positionOptions=u.isManager()&&u.departmentId()!=null?departments.positionsByDepartment(u.departmentId()).stream().filter(p->((Number)p.get("is_manager_position")).intValue()==0).toList():List.of();
        positionOptions.forEach(p->p.remove("position_salary"));

        model.addAttribute("employees",result.rows());
        model.addAttribute("pagination",result.pagination());
        model.addAttribute("departments",departmentOptions);
        model.addAttribute("positions",positionOptions);
        model.addAttribute("keyword",query.getKeyword());
        model.addAttribute("departmentId",query.getDepartmentId());
        model.addAttribute("status",query.getStatus());
        model.addAttribute("returnContext",query);
        return "employees";
    }

    /** 员工详情（含只读完整档案），仅系统高层可查看他人；本人请使用“个人档案”。 */
    @GetMapping("/{employeeNo}")
    public String detail(@PathVariable String employeeNo,
                         @RequestParam(required=false)String returnKeyword,@RequestParam(required=false)String returnDepartmentId,@RequestParam(required=false)String returnStatus,
                         @RequestParam(required=false)String returnPage,@RequestParam(required=false)String returnPageSize,
                         HttpSession s,Model m){
        SessionUser u=user(s);
        if(!u.isExecutive()) throw new ForbiddenException("员工完整档案仅限系统高层查看");
        Map<String,Object> employee=service.detail(employeeNo,u);
        m.addAttribute("employee",employee);
        m.addAttribute("archive",profiles.archive(((Number)employee.get("id")).longValue()));
        m.addAttribute("profileEditable",false);
        m.addAttribute("returnContext",EmployeeListQuery.fromReturnParams(returnKeyword,returnDepartmentId,returnStatus,returnPage,returnPageSize));
        return "employee-detail";
    }

    @PostMapping("/recruit") public String recruit(@RequestParam String name,@RequestParam String identityNumber,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate birthDate,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hireDate,@RequestParam(required=false)Long departmentId,@RequestParam(required=false)Long positionId,HttpSession s,RedirectAttributes ra){
        SessionUser u=user(s);try{if(!u.isExecutive())throw new IllegalArgumentException("只有高层可以招聘员工");String no=service.recruit(name,identityNumber,birthDate,hireDate,departmentId,positionId,0,u.id());ra.addFlashAttribute("success","招聘成功，新员工工号："+no);}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}return "redirect:/employees";
    }

    @PostMapping("/{employeeNo}/department")
    public String department(@PathVariable String employeeNo,@RequestParam Long departmentId,@RequestParam String reason,
                             @RequestParam(required=false)String returnKeyword,@RequestParam(required=false)String returnDepartmentId,@RequestParam(required=false)String returnStatus,
                             @RequestParam(required=false)String returnPage,@RequestParam(required=false)String returnPageSize,
                             HttpSession s,RedirectAttributes ra){
        try{service.changeDepartment(employeeNo,departmentId,reason,user(s));ra.addFlashAttribute("success","员工已调入新部门，等待新部门经理分配职位");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}
        return back(returnKeyword,returnDepartmentId,returnStatus,returnPage,returnPageSize);
    }

    @PostMapping("/{employeeNo}/position")
    public String position(@PathVariable String employeeNo,@RequestParam Long positionId,@RequestParam String reason,
                           @RequestParam(required=false)String returnKeyword,@RequestParam(required=false)String returnDepartmentId,@RequestParam(required=false)String returnStatus,
                           @RequestParam(required=false)String returnPage,@RequestParam(required=false)String returnPageSize,
                           HttpSession s,RedirectAttributes ra){
        try{service.changePosition(employeeNo,positionId,reason,user(s));ra.addFlashAttribute("success","员工职位调整成功");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}
        return back(returnKeyword,returnDepartmentId,returnStatus,returnPage,returnPageSize);
    }

    @PostMapping("/{employeeNo}/terminate")
    public String terminate(@PathVariable String employeeNo,@RequestParam String reason,
                            @RequestParam(required=false)String returnKeyword,@RequestParam(required=false)String returnDepartmentId,@RequestParam(required=false)String returnStatus,
                            @RequestParam(required=false)String returnPage,@RequestParam(required=false)String returnPageSize,
                            HttpSession s,RedirectAttributes ra){
        try{service.terminate(employeeNo,reason,user(s));ra.addFlashAttribute("success","离职处理完成");}catch(IllegalArgumentException e){ra.addFlashAttribute("error",e.getMessage());}
        return back(returnKeyword,returnDepartmentId,returnStatus,returnPage,returnPageSize);
    }

    /** 业务操作后回到原查询；隐藏域参数经过校验，非法值被丢弃。 */
    private String back(String keyword,String departmentId,String status,String page,String pageSize){
        return "redirect:"+EmployeeListQuery.fromReturnParams(keyword,departmentId,status,page,pageSize).url();
    }
    private SessionUser user(HttpSession s){return (SessionUser)s.getAttribute("user");}
}
