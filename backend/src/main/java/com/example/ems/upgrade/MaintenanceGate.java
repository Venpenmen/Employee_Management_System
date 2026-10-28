package com.example.ems.upgrade;
import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.*;import java.nio.file.*;import java.util.concurrent.*;import java.util.concurrent.locks.*;
@Component public class MaintenanceGate extends OncePerRequestFilter {
 private final ReentrantReadWriteLock gate=new ReentrantReadWriteLock(true);
 private final ScheduledExecutorService watcher=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ems-maintenance-gate");t.setDaemon(true);return t;});
 private final DatabaseJobs jobs;
 public MaintenanceGate(DatabaseJobs j){jobs=j;}
 Path dir(){return jobs.root().resolve("data/"+jobs.mode());}
 @jakarta.annotation.PostConstruct void start(){watcher.scheduleWithFixedDelay(()->{try{Path flag=dir().resolve("maintenance.flag");if(Files.exists(flag)){gate.writeLock().lock();try{Files.writeString(dir().resolve("quiesced"),Files.readString(flag));}finally{gate.writeLock().unlock();}}}catch(Exception ignored){}},1,1,TimeUnit.SECONDS);}
 protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws ServletException,IOException{
  boolean maintenance=false;try{maintenance=Files.exists(dir().resolve("maintenance.flag"));}catch(IllegalStateException ignored){}
  if(maintenance&&!r.getRequestURI().equals("/health")){s.setStatus(503);s.setContentType("application/json;charset=UTF-8");s.getWriter().write("{\"code\":503,\"msg\":\"数据库维护中，请稍后重新登录\",\"data\":null}");return;}
  gate.readLock().lock();try{c.doFilter(r,s);}finally{gate.readLock().unlock();}
 }
 @jakarta.annotation.PreDestroy void stop(){watcher.shutdownNow();}
}
