package com.example.ems.config;
import org.springframework.stereotype.Component;import org.springframework.context.event.EventListener;import org.springframework.boot.context.event.ApplicationReadyEvent;import org.springframework.context.event.ContextClosedEvent;import org.springframework.web.servlet.HandlerInterceptor;import jakarta.servlet.http.*;
@Component public class ApplicationReadiness implements HandlerInterceptor {
 private volatile boolean ready;
 @EventListener(ApplicationReadyEvent.class) public void ready(){ready=true;}
 @EventListener(ContextClosedEvent.class) public void closed(){ready=false;}
 public boolean isReady(){return ready;}
 public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
  String path=com.example.ems.web.RequestPaths.path(request);
  if(request.getDispatcherType()==jakarta.servlet.DispatcherType.ASYNC&&"/api/security/events".equals(path)&&Boolean.TRUE.equals(request.getAttribute("ems.sse.authorized")))return true;
  if(ready)return true;
  response.setStatus(503);response.setHeader("Cache-Control","no-store");
  if(path.equals("/health")){response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"status\":\"DOWN\"}");}
  else if(path.startsWith("/api/")){response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":503,\"msg\":\"服务尚未就绪或正在停止，请稍后重试\",\"data\":null}");}
  else{response.setContentType("text/html;charset=UTF-8");response.getWriter().write("<!doctype html><meta charset=utf-8><p>服务尚未就绪或正在停止，请稍后重试。</p>");}return false;
 }
}
