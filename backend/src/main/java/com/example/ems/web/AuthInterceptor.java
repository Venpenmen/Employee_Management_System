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
        if(request.getDispatcherType()==jakarta.servlet.DispatcherType.ASYNC&&"/api/security/events".equals(RequestPaths.path(request))&&Boolean.TRUE.equals(request.getAttribute("ems.sse.authorized")))return true;
        var session=request.getSession(false);SessionUser user=null;String observedToken=null;try{if(session!=null)synchronized(session){user=(SessionUser)session.getAttribute("user");observedToken=com.example.ems.service.SessionService.token(session);}}catch(IllegalStateException closed){unauthenticated(request,response);return false;}
        if(user!=null){request.setAttribute("ems.auth.observedUserId",user.id());request.setAttribute("ems.auth.observedToken",observedToken);}
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
            com.example.ems.service.SessionService.invalidateObserved(session,user.id(),observedToken); unauthenticated(request,response); return false;
        }
        var validity=sessions.validate(user.id(),observedToken);
        if(validity!=com.example.ems.service.SessionService.Validity.VALID){
            com.example.ems.service.SessionService.invalidateObserved(session,user.id(),observedToken);
            if(RequestPaths.path(request).startsWith("/api/")){
                response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
                var body=new java.util.LinkedHashMap<String,Object>();
                body.put("code",401);body.put("msg",validity==com.example.ems.service.SessionService.Validity.REPLACED?"账号已在其他设备登录":"登录已失效，请重新登录");body.put("data",null);
                response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
            }else response.sendRedirect("/login?reason="+(validity==com.example.ems.service.SessionService.Validity.REPLACED?"other-login":"expired"));
            return false;
        }
        String securityPath=RequestPaths.path(request);
        var securityRows=jdbc.queryForList("SELECT frozen,temporary_state,temporary_expires_at>NOW(6) temporary_valid FROM account_security WHERE employee_id=?",user.id());
        if(!securityRows.isEmpty()){
            var securityState=securityRows.get(0);boolean frozen=((Number)securityState.get("frozen")).intValue()!=0;String temporary=String.valueOf(securityState.get("temporary_state"));
            if(frozen||"REISSUE".equals(temporary)||("TEMP".equals(temporary)&&!com.example.ems.security.SecurityService.truth(securityState.get("temporary_valid")))){com.example.ems.service.SessionService.invalidateObserved(session,user.id(),observedToken);unauthenticated(request,response);return false;}
            if("TEMP".equals(temporary)&&!java.util.Set.of("/security/temporary-password","/api/security/temporary-password","/logout").contains(securityPath)){
                if(securityPath.startsWith("/api/"))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"临时密码登录必须先设置正式密码");
                response.sendRedirect("/security/temporary-password");return false;
            }
        }
        var row=rows.get(0);
        Long departmentId=row.get("department_id")==null?null:((Number)row.get("department_id")).longValue();
        user=new SessionUser(((Number)row.get("id")).longValue(),String.valueOf(row.get("employee_no")),String.valueOf(row.get("name")),((Number)row.get("user_type")).intValue(),departmentId,row.get("department_name")==null?"未分配":String.valueOf(row.get("department_name")));
        try{synchronized(session){Object current=session.getAttribute("user");if(!(current instanceof SessionUser identity)||!identity.id().equals(user.id())||!java.util.Objects.equals(com.example.ems.service.SessionService.token(session),observedToken)){unauthenticated(request,response);return false;}session.setAttribute("user",user);request.setAttribute("profileCsrf",session.getAttribute("profileCsrf"));}}catch(IllegalStateException closed){unauthenticated(request,response);return false;}
        request.setAttribute("currentUser", user);
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
