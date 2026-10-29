package com.example.ems.session;
import com.example.ems.service.*;
import com.example.ems.model.SessionUser;
import com.example.ems.config.SessionLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import jakarta.servlet.http.HttpSessionEvent;
import java.util.*;
import java.util.concurrent.*;
public class SessionDatabaseIT {
 static int count;
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);count++;System.out.println("PASS: "+name);}
 static void rejects(Runnable work,String name){boolean rejected=false;try{work.run();}catch(RuntimeException e){rejected=true;}check(rejected,name);}
 @EnableTransactionManagement @Import({SessionService.class,AuthService.class,LogService.class})
 static class Config {
  @Bean javax.sql.DataSource dataSource(){
   String url=System.getenv("EMS_IT_URL");
   if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(1650[6-9]|165[12][0-9]|16530)/ems_up_server_v120\\?.*"))throw new IllegalStateException("Unsafe test database");
   return new DriverManagerDataSource(url,"ems_app",System.getenv("EMS_DB_PASSWORD"));
  }
  @Bean JdbcTemplate jdbcTemplate(javax.sql.DataSource d){return new JdbcTemplate(d);}
  @Bean PlatformTransactionManager transactionManager(javax.sql.DataSource d){return new DataSourceTransactionManager(d);}
 }
 static void bind(Long id,String token){
  var request=new MockHttpServletRequest();var session=new MockHttpSession();
  session.setAttribute("user",new SessionUser(id,"260001","娴嬭瘯",2,null,""));session.setAttribute(SessionService.TOKEN,token);
  request.setSession(session);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
 }
 public static void main(String[] args) throws Exception{
  try(var c=new AnnotationConfigApplicationContext(Config.class)){
   var sessions=c.getBean(SessionService.class);var auth=c.getBean(AuthService.class);var jdbc=c.getBean(JdbcTemplate.class);
   var tx=new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
   String password=System.getenv("EMS_TEST_PASSWORD");
   Long id=jdbc.queryForObject("SELECT id FROM employee WHERE employee_no='260001'",Long.class);
   Long other=jdbc.queryForObject("SELECT id FROM employee WHERE employee_no='260003'",Long.class);
   String a=sessions.issue(id,password),b=sessions.issue(id,password);
   check(sessions.validate(id,a)==SessionService.Validity.REPLACED,"Second committed login replaces first");
   check(sessions.validate(id,b)==SessionService.Validity.VALID,"Newest token valid");
   sessions.revoke(id,a);check(sessions.validate(id,b)==SessionService.Validity.VALID,"Old conditional logout preserves newest token");
   var expired=new MockHttpSession();expired.setAttribute("user",new SessionUser(id,"260001","娴嬭瘯",2,null,""));expired.setAttribute(SessionService.TOKEN,a);
   new SessionLifecycle().sessionListener(sessions).getListener().sessionDestroyed(new HttpSessionEvent(expired));
   check(sessions.validate(id,b)==SessionService.Validity.VALID,"Destroyed old session cannot revoke new session");
   rejects(()->auth.login("260001","WrongPassword1"),"Wrong password rejected");
   check(sessions.validate(id,b)==SessionService.Validity.VALID,"Wrong password does not replace session");
   rejects(()->sessions.issue(id,"WrongPassword1"),"Final transaction rechecks credential");
   check(sessions.validate(id,b)==SessionService.Validity.VALID,"Rejected final credential preserves prior token");
   jdbc.update("UPDATE user_account SET account_status=0 WHERE employee_id=?",id);
   rejects(()->sessions.issue(id,password),"Final transaction checks disabled account");
   jdbc.update("UPDATE user_account SET account_status=1,failed_login_count=0 WHERE employee_id=?",id);
   check(sessions.validate(id,b)==SessionService.Validity.VALID,"Disabled login did not overwrite digest");
   var executor=Executors.newFixedThreadPool(2);
   try{
    var one=executor.submit(()->sessions.issue(id,password));var two=executor.submit(()->sessions.issue(id,password));
    String first=one.get(15,TimeUnit.SECONDS),second=two.get(15,TimeUnit.SECONDS);
    check((sessions.validate(id,first)==SessionService.Validity.VALID?1:0)+(sessions.validate(id,second)==SessionService.Validity.VALID?1:0)==1,"Concurrent commits leave exactly one valid token");
    check(jdbc.queryForObject("SELECT COUNT(*) FROM account_active_session WHERE employee_id=?",Integer.class,id)==1,"Database unique employee constraint holds");
    String current=sessions.issue(id,password),independent=sessions.issue(other,password);
    check(sessions.validate(id,current)==SessionService.Validity.VALID&&sessions.validate(other,independent)==SessionService.Validity.VALID,"Different employee sessions independent");
    bind(id,current);
    var badLog=new LogService(jdbc){@Override public void log(Long who,String op,String type,Long target,String summary){throw new IllegalStateException("Injected audit failure");}};
    rejects(()->sessions.changePassword(id,password,password+"Z1",badLog),"Injected audit failure rolls back password transaction");
    check(sessions.validate(id,current)==SessionService.Validity.VALID,"Password rollback restores active digest");
    auth.login("260001",password);check(true,"Password rollback restores old password");
    String replacement=sessions.issue(id,password);
    rejects(()->sessions.changePassword(id,password,password+"Z1",c.getBean(LogService.class)),"Stale request cannot change password after replacement");
    check(sessions.validate(id,replacement)==SessionService.Validity.VALID,"Stale password request preserves winner");
    bind(id,replacement);sessions.changePassword(id,password,password+"Z1",c.getBean(LogService.class));
    check(sessions.validate(id,replacement)==SessionService.Validity.EXPIRED,"Successful password change revokes current session");
    auth.login("260001",password+"Z1");String changed=sessions.issue(id,password+"Z1");
    bind(id,changed);sessions.changePassword(id,password+"Z1",password,c.getBean(LogService.class));
    check(sessions.validate(id,changed)==SessionService.Validity.EXPIRED,"Restoring test password also forces reauthentication");
    String gate=sessions.issue(id,password);
    CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1),attempted=new CountDownLatch(1);
    var guarded=executor.submit(()->{
     bind(id,gate);
     try{return tx.execute(s->{sessions.guardCurrent(id,2);acquired.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Gate timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}return true;});}
     finally{RequestContextHolder.resetRequestAttributes();}
    });
    if(!acquired.await(10,TimeUnit.SECONDS))throw new AssertionError("Guard not acquired");
    var login=executor.submit(()->{attempted.countDown();return sessions.issue(id,password);});
    attempted.await();Thread.sleep(250);
    check(!login.isDone(),"New login waits for high-risk transaction account lock");
    release.countDown();guarded.get(15,TimeUnit.SECONDS);String winner=login.get(15,TimeUnit.SECONDS);
    check(sessions.validate(id,winner)==SessionService.Validity.VALID,"New login commits after protected transaction");
    bind(id,gate);
    rejects(()->tx.execute(s->{sessions.guardCurrent(id,2);throw new AssertionError("Must not execute business mutation");}),"High-risk transaction rejects superseded token before business code");
    sessions.revoke(id,winner);check(sessions.validate(id,winner)==SessionService.Validity.EXPIRED,"Current logout removes only matching digest");
    sessions.revoke(other,independent);
   }finally{executor.shutdownNow();RequestContextHolder.resetRequestAttributes();}
   System.out.println("Database session checks passed: "+count);
  }
 }
}
