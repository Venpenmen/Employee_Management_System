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
    private final com.example.ems.service.SessionService sessions;
    public AuthInterceptor(JdbcTemplate jdbc,com.example.ems.service.SessionService sessions) { this.jdbc = jdbc;this.sessions=sessions; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        SessionUser user = (SessionUser) (request.getSession(false)==null?null:request.getSession(false).getAttribute("user"));
        if (user == null) {
            unauthenticated(request,response);
            return false;
        }
        var rows = jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,e.user_type,e.department_id,e.employment_status,
                   a.account_status,d.department_name
            FROM employee e JOIN user_account a ON a.employee_id=e.id
            LEFT JOIN department d ON d.id=e.department_id WHERE e.id=?
            """, user.id());
        if (rows.isEmpty() || ((Number)rows.get(0).get("employment_status")).intValue()!=1 || ((Number)rows.get(0).get("account_status")).intValue()!=1) {
            request.getSession().invalidate(); unauthenticated(request,response); return false;
        }
        var validity=sessions.validate(user.id(),com.example.ems.service.SessionService.token(request.getSession(false)));
        if(validity!=com.example.ems.service.SessionService.Validity.VALID){
            request.getSession().invalidate();
            if(RequestPaths.path(request).startsWith("/api/")){
                response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
                var body=new java.util.LinkedHashMap<String,Object>();
                body.put("code",401);body.put("msg",validity==com.example.ems.service.SessionService.Validity.REPLACED?"账号已在其他设备登录":"登录已失效，请重新登录");body.put("data",null);
                response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
            }else response.sendRedirect("/login?reason="+(validity==com.example.ems.service.SessionService.Validity.REPLACED?"other-login":"expired"));
            return false;
        }
        var row=rows.get(0);
        Long departmentId=row.get("department_id")==null?null:((Number)row.get("department_id")).longValue();
        user=new SessionUser(((Number)row.get("id")).longValue(),String.valueOf(row.get("employee_no")),String.valueOf(row.get("name")),((Number)row.get("user_type")).intValue(),departmentId,row.get("department_name")==null?"未分配":String.valueOf(row.get("department_name")));
        request.getSession().setAttribute("user",user);
        request.setAttribute("currentUser", user);
        request.setAttribute("profileCsrf",request.getSession().getAttribute("profileCsrf"));
        String path = RequestPaths.path(request);
        boolean adminHandler=handler instanceof org.springframework.web.method.HandlerMethod method && AdminController.class.isAssignableFrom(method.getBeanType());
        if ((adminHandler || path.startsWith("/admin/") || path.equals("/admin")) && !user.isExecutive()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"无权执行此操作");
        }
        if ((path.startsWith("/attendance/manage") || path.startsWith("/attendance/rule") || path.matches("/attendance/\\d+/correct") || path.startsWith("/leaves/approvals")) && !user.isManager() && !user.isExecutive()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"无权执行此操作");
        }
        return true;
    }
    private void unauthenticated(HttpServletRequest request,HttpServletResponse response) throws java.io.IOException {
        if(RequestPaths.path(request).startsWith("/api/")){
            response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"登录已失效，请重新登录\",\"data\":null}");
        }else response.sendRedirect("/login");
    }
}
