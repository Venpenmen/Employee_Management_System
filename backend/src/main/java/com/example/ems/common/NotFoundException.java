package com.example.ems.common;

/** 业务异常：对应 HTTP 404。消息会展示给用户，不得包含底层异常细节。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException() { super("请求的资源不存在"); }
    public NotFoundException(String message) { super(message); }
}
