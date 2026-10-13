package com.example.ems.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public String handle(Exception ex, HttpServletRequest request, Model model) {
        model.addAttribute("message", rootMessage(ex));
        model.addAttribute("path", request.getRequestURI());
        return "error-page";
    }
    private String rootMessage(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage()==null?"系统处理失败":e.getMessage();}
}
