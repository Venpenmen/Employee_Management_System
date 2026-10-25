package com.example.ems.common;

/** 业务异常：对应 HTTP 403。消息会展示给用户，不得包含底层异常细节。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException() { super("无权执行该操作"); }
    public ForbiddenException(String message) { super(message); }
}
