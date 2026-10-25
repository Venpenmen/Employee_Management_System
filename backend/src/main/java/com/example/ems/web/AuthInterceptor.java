package com.example.ems.web;

import com.example.ems.common.ForbiddenException;
import com.example.ems.model.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 登录与角色检查。每次请求都从数据库刷新会话用户，离职或停用后立即失效。
 * 未登录访问 API 返回 401 JSON，访问页面跳转登录；越权访问抛出 403。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;
    public AuthInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        SessionUser user = session == null ? null : (SessionUser) session.getAttribute("user");
        if (user == null) return unauthenticated(request, response);
        var rows = jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,e.user_type,e.department_id,e.employment_status,
                   a.account_status,d.department_name
            FROM employee e JOIN user_account a ON a.employee_id=e.id
            LEFT JOIN department d ON d.id=e.department_id WHERE e.id=?
            """, user.id());
        if (rows.isEmpty() || ((Number) rows.get(0).get("employment_status")).intValue() != 1
                || ((Number) rows.get(0).get("account_status")).intValue() != 1) {
            session.invalidate();
            return unauthenticated(request, response);
        }
        var row = rows.get(0);
        Long departmentId = row.get("department_id") == null ? null : ((Number) row.get("department_id")).longValue();
        user = new SessionUser(((Number) row.get("id")).longValue(), String.valueOf(row.get("employee_no")), String.valueOf(row.get("name")),
                ((Number) row.get("user_type")).intValue(), departmentId,
                row.get("department_name") == null ? "未分配" : String.valueOf(row.get("department_name")));
        session.setAttribute("user", user);
        request.setAttribute("currentUser", user);

        String path = path(request);
        if ((path.startsWith("/admin/") || path.equals("/admin")) && !user.isExecutive())
            throw new ForbiddenException("该功能仅限系统高层使用");
        if ((path.startsWith("/attendance/manage") || path.startsWith("/attendance/rule") || path.matches("/attendance/\\d+/correct")
                || path.startsWith("/leaves/approvals")) && !user.isManager() && !user.isExecutive())
            throw new ForbiddenException("该功能仅限部门经理和系统高层使用");
        return true;
    }

    static boolean isApi(HttpServletRequest request) {
        String path = path(request);
        return path.equals("/api") || path.startsWith("/api/");
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private boolean unauthenticated(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (isApi(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"登录已失效，请重新登录\",\"data\":null}");
            return false;
        }
        response.sendRedirect(request.getContextPath() + "/login");
        return false;
    }
}
