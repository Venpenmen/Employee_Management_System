package com.example.ems.web;

import com.example.ems.model.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;
    public AuthInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        SessionUser user = (SessionUser) request.getSession().getAttribute("user");
        if (user == null) {
            response.sendRedirect("/login");
            return false;
        }
        var rows = jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,e.user_type,e.department_id,e.employment_status,
                   a.account_status,d.department_name
            FROM employee e JOIN user_account a ON a.employee_id=e.id
            LEFT JOIN department d ON d.id=e.department_id WHERE e.id=?
            """, user.id());
        if (rows.isEmpty() || ((Number)rows.get(0).get("employment_status")).intValue()!=1 || ((Number)rows.get(0).get("account_status")).intValue()!=1) {
            request.getSession().invalidate(); response.sendRedirect("/login"); return false;
        }
        var row=rows.get(0);
        Long departmentId=row.get("department_id")==null?null:((Number)row.get("department_id")).longValue();
        user=new SessionUser(((Number)row.get("id")).longValue(),String.valueOf(row.get("employee_no")),String.valueOf(row.get("name")),((Number)row.get("user_type")).intValue(),departmentId,row.get("department_name")==null?"未分配":String.valueOf(row.get("department_name")));
        request.getSession().setAttribute("user",user);
        request.setAttribute("currentUser", user);
        String path = request.getRequestURI();
        if ((path.startsWith("/admin/") || path.equals("/admin")) && !user.isExecutive()) {
            response.sendRedirect("/dashboard?error=noPermission");
            return false;
        }
        if ((path.startsWith("/attendance/manage") || path.startsWith("/attendance/rule") || path.matches("/attendance/\\d+/correct") || path.startsWith("/leaves/approvals")) && !user.isManager() && !user.isExecutive()) {
            response.sendRedirect("/dashboard?error=noPermission");
            return false;
        }
        return true;
    }
}
