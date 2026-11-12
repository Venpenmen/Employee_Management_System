package com.example.ems.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void disconnected(org.springframework.web.context.request.async.AsyncRequestNotUsableException error,jakarta.servlet.http.HttpServletResponse response){/* The transport is closed; no second write to an SSE response. */}
    @ExceptionHandler(Exception.class)
    public Object handle(Exception error,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
        UserErrors.Failure failure=UserErrors.describe(error);
        if("/api/security/events".equals(RequestPaths.path(request))){
            if(failure.status()==401&&request.getAttribute("ems.auth.observedUserId") instanceof Long observedId)com.example.ems.service.SessionService.invalidateObserved(request.getSession(false),observedId,(String)request.getAttribute("ems.auth.observedToken"));
            if(!response.isCommitted()){response.setStatus(failure.status());response.setContentType("application/json;charset=UTF-8");var body=new LinkedHashMap<String,Object>();body.put("code",failure.status());body.put("msg",failure.message());body.put("data",null);new com.fasterxml.jackson.databind.ObjectMapper().writeValue(response.getWriter(),body);}return null;
        }
        if(failure.status()==401){
            if(request.getAttribute("ems.auth.observedUserId") instanceof Long observedId)com.example.ems.service.SessionService.invalidateObserved(request.getSession(false),observedId,(String)request.getAttribute("ems.auth.observedToken"));else com.example.ems.service.SessionService.invalidateQuietly(request.getSession(false));
            if(!RequestPaths.path(request).startsWith("/api/"))return new ModelAndView("redirect:/login?reason=expired");
        }
        if(RequestPaths.path(request).startsWith("/api/")){
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("code",failure.status());body.put("msg",failure.message());body.put("data",null);
            return ResponseEntity.status(failure.status()).body(body);
        }
        ModelAndView page=new ModelAndView("error-page");
        page.setStatus(org.springframework.http.HttpStatusCode.valueOf(failure.status()));
        page.addObject("message",failure.message());page.addObject("path",request.getRequestURI());
        return page;
    }
}
