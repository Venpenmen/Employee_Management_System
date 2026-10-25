package com.example.ems.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * 容器级错误页（控制器之外的异常，例如模板渲染失败、过滤器异常、未登录用户访问不存在的地址）。
 * 替代 Spring Boot 默认错误页，不输出异常类名、消息或堆栈；500 错误记录错误编号。
 */
@Controller
public class SafeErrorController implements ErrorController {
    private final ErrorResponses errors;
    public SafeErrorController(ErrorResponses errors) { this.errors = errors; }

    @RequestMapping("/error")
    public Object error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = HttpStatus.resolve(code instanceof Integer i ? i : 500);
        if (status == null || !status.isError()) status = HttpStatus.INTERNAL_SERVER_ERROR;
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uri == null ? request.getRequestURI() : uri.toString();
        String message;
        if (status.is5xxServerError()) {
            Object ex = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            String errorId = errors.logUnexpected(ex instanceof Throwable t ? t : null, request, path);
            message = ErrorResponses.unexpectedMessage(errorId);
        } else {
            message = ErrorResponses.defaultMessage(status);
        }
        Object response = errors.respond(request, status, message, path);
        if (response instanceof ModelAndView mv) mv.setViewName("error"); // error.html 不依赖登录信息
        return response;
    }
}
