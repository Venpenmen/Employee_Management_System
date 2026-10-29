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
    @ExceptionHandler(Exception.class)
    public Object handle(Exception error,HttpServletRequest request){
        UserErrors.Failure failure=UserErrors.describe(error);
        if(failure.status()==401){
            if(request.getSession(false)!=null)request.getSession(false).invalidate();
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
