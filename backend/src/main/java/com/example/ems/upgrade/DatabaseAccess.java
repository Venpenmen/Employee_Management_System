package com.example.ems.upgrade;
import com.example.ems.model.SessionUser;import com.example.ems.service.*;
import jakarta.servlet.http.*;import org.springframework.stereotype.Service;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
import java.util.*;import java.util.concurrent.*;
@Service public class DatabaseAccess {
 record Grant(long user,String token,String action,String object,long expires){}
 private final Map<String,Grant> grants=new ConcurrentHashMap<>();private final Map<Long,long[]> attempts=new ConcurrentHashMap<>();
 private final SessionService sessions;private final AuthService auth;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
 public DatabaseAccess(SessionService s,AuthService a,JdbcTemplate j,org.springframework.transaction.PlatformTransactionManager tm){sessions=s;auth=a;jdbc=j;tx=new TransactionTemplate(tm);}
 public SessionUser user(HttpServletRequest req){
  String remote=req.getRemoteAddr();if(!req.isSecure()&&!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(remote))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"数据库管理仅允许本机或HTTPS");
  if(!req.isSecure()&&(req.getHeader("Forwarded")!=null||req.getHeader("X-Forwarded-For")!=null||req.getHeader("X-Forwarded-Proto")!=null||!Set.of("localhost","127.0.0.1","::1","[::1]").contains(req.getServerName())))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"代理访问数据库管理必须使用可信HTTPS");
  var session=req.getSession(false);Object value=session==null?null:session.getAttribute("user");
  if(!(value instanceof SessionUser u)||!u.isExecutive())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只有高层可管理数据库");
  tx.executeWithoutResult(x->sessions.guardCurrent(u.id(),2));return u;
 }
 public String issue(HttpServletRequest req,String password,String action,String object){
  var u=user(req);if(!Set.of("export","inspect","commit").contains(action))throw new IllegalArgumentException("操作类型不正确");
  long now=System.currentTimeMillis();var failure=attempts.computeIfAbsent(u.id(),k->new long[]{0,now});
  synchronized(failure){
   if(now-failure[1]>900000){failure[0]=0;failure[1]=now;}
   if(failure[0]>=5)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"二次验证次数过多，请15分钟后重试");
   try{auth.login(u.employeeNo(),password);tx.executeWithoutResult(x->{sessions.guardCurrent(u.id(),2);String hash=jdbc.queryForObject("SELECT password_hash FROM user_account WHERE employee_id=? FOR UPDATE",String.class,u.id());if(!new BCryptPasswordEncoder().matches(password,hash))throw new IllegalArgumentException("密码已变化");});}
   catch(RuntimeException e){failure[0]++;throw e;}
  }
  grants.entrySet().removeIf(e->e.getValue().expires<now);
  String id=UUID.randomUUID().toString();grants.put(id,new Grant(u.id(),SessionService.digest(SessionService.token(req.getSession(false))),action,object==null?"":object,now+120000));return id;
 }
 public SessionUser consume(HttpServletRequest req,String id,String action,String object){
  var u=user(req);Grant g=grants.remove(id);
  if(g==null||g.user!=u.id()||g.expires<System.currentTimeMillis()||!g.action.equals(action)||!g.object.equals(object==null?"":object)||!g.token.equals(SessionService.digest(SessionService.token(req.getSession(false)))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"操作授权已失效，请重新验证");
  return u;
 }
}
