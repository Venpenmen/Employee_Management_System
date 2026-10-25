package com.example.ems.common;

/** 业务异常：对应 HTTP 409。消息会展示给用户，不得包含底层异常细节。 */
public class ConflictException extends RuntimeException {
    public ConflictException() { super("数据已被其他操作修改"); }
    public ConflictException(String message) { super(message); }
}
