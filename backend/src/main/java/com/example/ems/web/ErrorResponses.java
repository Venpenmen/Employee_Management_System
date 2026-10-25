package com.example.ems.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 统一生成错误响应：页面请求渲染错误页，/api 请求返回 JSON。
 * 非预期异常只向用户显示错误编号，底层异常和堆栈仅写入服务端日志。
 */
@Component
public class ErrorResponses {
    private static final Logger log = LoggerFactory.getLogger(ErrorResponses.class);

    /** 生成错误编号并记录完整异常。 */
    public String logUnexpected(Throwable ex, HttpServletRequest request, String path) {
        String errorId = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        log.error("Unexpected error [{}] {} {}", errorId, request.getMethod(), path, ex);
        return errorId;
    }

    public static String unexpectedMessage(String errorId) {
        return "系统处理失败，请稍后重试。如问题持续，请将错误编号 " + errorId + " 提供给管理员。";
    }

    public static String defaultMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "请求参数无效，请检查后重试";
            case UNAUTHORIZED -> "登录已失效，请重新登录";
            case FORBIDDEN -> "您没有执行该操作的权限";
            case NOT_FOUND -> "请求的页面或数据不存在";
            case METHOD_NOT_ALLOWED -> "不支持该请求方式";
            case CONFLICT -> "数据已被其他操作修改，请刷新后重试";
            default -> "系统处理失败，请稍后重试";
        };
    }

    public Object respond(HttpServletRequest request, HttpStatus status, String message, String path) {
        if (isApi(path)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", status.value());
            body.put("msg", message);
            body.put("data", null);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ModelAndView mv = new ModelAndView("error-page");
        mv.setStatus(status);
        mv.addObject("message", message);
        mv.addObject("path", path);
        mv.addObject("status", status.value());
        return mv;
    }

    static boolean isApi(String path) { return path != null && (path.equals("/api") || path.startsWith("/api/")); }
}
