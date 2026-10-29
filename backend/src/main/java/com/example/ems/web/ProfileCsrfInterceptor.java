package com.example.ems.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
public class ProfileCsrfInterceptor implements HandlerInterceptor {
    private static final String KEY="profileCsrf";
    @Override
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
        String path=RequestPaths.path(request);
        if(path.startsWith("/internal/portable/")) return true;
        var session=request.getSession();
        String token;
        synchronized(session) {
            token=(String)session.getAttribute(KEY);
            if(token==null){token=UUID.randomUUID().toString()+UUID.randomUUID();session.setAttribute(KEY,token);}
        }
        request.setAttribute(KEY,token);
        if(!"GET".equals(request.getMethod())&&!"HEAD".equals(request.getMethod())&&!"OPTIONS".equals(request.getMethod())) {
            String supplied=request.getParameter("_csrf");
            if(supplied==null) supplied=request.getHeader("X-CSRF-Token");
            if(supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(403);
                if(path.startsWith("/api/")) {
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":403,\"msg\":\"表单已失效，请刷新页面后重试\",\"data\":null}");
                } else {
                    response.setContentType("text/html;charset=UTF-8");
                    response.getWriter().write("<!doctype html><meta charset='utf-8'><title>403</title><p>表单已失效，请刷新页面后重试</p>");
                }
                return false;
            }
        }
        return true;
    }
}
