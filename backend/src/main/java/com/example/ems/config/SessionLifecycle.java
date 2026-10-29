package com.example.ems.config;
import com.example.ems.service.SessionService;
import com.example.ems.model.SessionUser;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;
@Configuration
public class SessionLifecycle {
 @Bean public ServletListenerRegistrationBean<HttpSessionListener> sessionListener(SessionService service){
  return new ServletListenerRegistrationBean<>(new HttpSessionListener(){
   public void sessionDestroyed(HttpSessionEvent event){
    try{
     var s=event.getSession();Object u=s.getAttribute("user");String token=SessionService.token(s);
     if(u instanceof SessionUser user && token!=null)service.revoke(user.id(),token);
    }catch(Exception e){org.slf4j.LoggerFactory.getLogger(SessionLifecycle.class).warn("Session cleanup incomplete [{}]",java.util.UUID.randomUUID());}
   }
  });
 }
}
