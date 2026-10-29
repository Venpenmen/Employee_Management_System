package com.example.ems.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

public final class UserErrors {
    private static final Logger LOG=LoggerFactory.getLogger(UserErrors.class);
    private UserErrors(){}
    public record Failure(int status,String message){}
    public static String message(Throwable error){
        Failure failure=describe(error);
        if(failure.status()==401)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,failure.message());
        if(failure.status()==403)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,failure.message());
        if(failure.status()==409)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,failure.message());
        if(failure.status()>=500)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,null,error);
        return failure.message();
    }
    public static Failure describe(Throwable error){
        if(error instanceof DataIntegrityViolationException)
            return new Failure(409,"数据已变化或与已有记录冲突，请刷新后重试");
        if(error instanceof DataAccessException)return unexpected(error);
        if(error instanceof MethodArgumentTypeMismatchException)
            return new Failure(400,"查询参数格式不正确，请检查页码、每页条数和筛选条件");
        if(error instanceof MissingServletRequestParameterException||error instanceof BindException
                ||error instanceof ConstraintViolationException)
            return new Failure(400,"提交的信息不完整或格式不正确，请检查后重试");
        if(error instanceof ResponseStatusException ex){
            int status=ex.getStatusCode().value();
            if(status>=500)return unexpected(error);
            return new Failure(status,ex.getReason()==null?defaultMessage(status):ex.getReason());
        }
        if(error instanceof ErrorResponse ex){
            int status=ex.getStatusCode().value();
            return status>=500?unexpected(error):new Failure(status,defaultMessage(status));
        }
        if(error instanceof IllegalArgumentException && error.getCause()==null
                && !(error instanceof NumberFormatException)){
            String message=error.getMessage();
            if(message!=null&&!message.matches("(?is).*(jdbc|sql|statement|constraint|duplicate entry|access denied|connection|datasource).*"))
                return new Failure(400,message);
        }
        return unexpected(error);
    }
    private static String defaultMessage(int status){
        return switch(status){
            case 400 -> "请求参数格式不正确，请检查后重试";
            case 401 -> "登录已失效，请重新登录";
            case 403 -> "您没有执行此操作的权限";
            case 404 -> "页面或记录不存在";
            case 405 -> "此操作不支持当前请求方式";
            case 409 -> "数据已变化，请刷新后重试";
            default -> "请求无法处理，请返回后重试";
        };
    }
    private static Failure unexpected(Throwable error){
        String reference=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
        LOG.error("EMS error {} [{}]",reference,error.getClass().getName(),error);
        return new Failure(500,"系统暂时无法完成操作，请稍后重试。错误编号："+reference);
    }
}
