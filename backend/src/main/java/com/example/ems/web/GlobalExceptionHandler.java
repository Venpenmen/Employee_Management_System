package com.example.ems.web;

import com.example.ems.common.ConflictException;
import com.example.ems.common.ForbiddenException;
import com.example.ems.common.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局区分 400 / 403 / 404 / 405 / 409 / 500。
 * 业务提示原样展示；非预期异常显示错误编号，底层信息只写服务端日志。
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    private final ErrorResponses errors;
    public GlobalExceptionHandler(ErrorResponses errors) { this.errors = errors; }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public Object duplicateKey(HttpServletRequest request) {
        return errors.respond(request, HttpStatus.CONFLICT, "数据已存在，请刷新后重试", request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return errors.respond(request, HttpStatus.BAD_REQUEST, message(ex, HttpStatus.BAD_REQUEST), request.getRequestURI());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, TypeMismatchException.class,
            HttpMessageNotReadableException.class, BindException.class, MultipartException.class})
    public Object invalidRequest(Exception ex, HttpServletRequest request) {
        String message = ex instanceof MaxUploadSizeExceededException ? "上传文件不能超过10MB" : ErrorResponses.defaultMessage(HttpStatus.BAD_REQUEST);
        return errors.respond(request, HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    @ExceptionHandler(ForbiddenException.class)
    public Object forbidden(ForbiddenException ex, HttpServletRequest request) {
        return errors.respond(request, HttpStatus.FORBIDDEN, message(ex, HttpStatus.FORBIDDEN), request.getRequestURI());
    }

    @ExceptionHandler({NotFoundException.class, NoResourceFoundException.class, NoHandlerFoundException.class})
    public Object notFound(Exception ex, HttpServletRequest request) {
        String message = ex instanceof NotFoundException ? message(ex, HttpStatus.NOT_FOUND) : ErrorResponses.defaultMessage(HttpStatus.NOT_FOUND);
        return errors.respond(request, HttpStatus.NOT_FOUND, message, request.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object methodNotAllowed(HttpServletRequest request) {
        return errors.respond(request, HttpStatus.METHOD_NOT_ALLOWED, ErrorResponses.defaultMessage(HttpStatus.METHOD_NOT_ALLOWED), request.getRequestURI());
    }

    @ExceptionHandler(ConflictException.class)
    public Object conflict(ConflictException ex, HttpServletRequest request) {
        return errors.respond(request, HttpStatus.CONFLICT, message(ex, HttpStatus.CONFLICT), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public Object unexpected(Exception ex, HttpServletRequest request) {
        String errorId = errors.logUnexpected(ex, request, request.getRequestURI());
        return errors.respond(request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponses.unexpectedMessage(errorId), request.getRequestURI());
    }

    private static String message(Exception ex, HttpStatus status) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ErrorResponses.defaultMessage(status) : ex.getMessage();
    }
}
