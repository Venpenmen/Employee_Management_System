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
        model.addAttribute("approverPendingCount",u.isManager()||u.isExecutive()?jdbc.queryForObject("SELECT COUNT(*) FROM leave_request WHERE current_approver_id=? AND status=1",Integer.class,u.id()):0);
        Long communicationUnread=jdbc.queryForObject("""
            SELECT
              (SELECT COUNT(*) FROM comm_message m
                WHERE m.sender_id<>? AND m.conversation_seq>(
                      SELECT s.last_read_seq FROM comm_conversation_user_state s
                       WHERE s.conversation_id=m.conversation_id AND s.employee_id=?)
                  AND EXISTS (SELECT 1 FROM comm_membership_period p
                               WHERE p.conversation_id=m.conversation_id AND p.employee_id=? AND p.open_marker=1
                                 AND m.conversation_seq>p.joined_after_seq))
              +
              (SELECT COUNT(*) FROM comm_announcement a
                JOIN comm_announcement_recipient r ON r.version_id=a.current_version_id
               WHERE r.employee_id=? AND a.announcement_status=1 AND r.read_at IS NULL)
            """,Long.class,u.id(),u.id(),u.id(),u.id());
        model.addAttribute("communicationUnread",communicationUnread==null?0:communicationUnread);
        model.addAttribute("departments",OptionViews.dashboard(departments.listDepartments(),u.isExecutive())); return "dashboard";
    }

}
