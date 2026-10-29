package com.example.ems.upgrade;
import com.example.ems.service.*;import com.example.ems.model.SessionUser;
import org.springframework.mock.web.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.*;import org.springframework.transaction.support.SimpleTransactionStatus;import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.*;import java.util.*;import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class DatabaseAccessTest {
 SessionService sessions=mock(SessionService.class);AuthService auth=mock(AuthService.class);JdbcTemplate jdbc=mock(JdbcTemplate.class);PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
 SessionUser executive=new SessionUser(1L,"260001","虚构高层",2,null,"");DatabaseAccess access;MockHttpServletRequest request;
 @BeforeEach void setup(){
  when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  when(jdbc.queryForObject("SELECT password_hash FROM user_account WHERE employee_id=? FOR UPDATE",String.class,1L)).thenReturn(new BCryptPasswordEncoder().encode("SyntheticPassword123"));
  access=new DatabaseAccess(sessions,auth,jdbc,manager);request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");request.setServerName("127.0.0.1");var session=new MockHttpSession();session.setAttribute("user",executive);session.setAttribute(SessionService.TOKEN,"a".repeat(43));request.setSession(session);
 }
 @Test void grantSingleUseAndBoundToAction(){
  String grant=access.issue(request,"SyntheticPassword123","export","");assertEquals(1L,access.consume(request,grant,"export","").id());assertThrows(Exception.class,()->access.consume(request,grant,"export",""));
  String other=access.issue(request,"SyntheticPassword123","inspect","");assertThrows(Exception.class,()->access.consume(request,other,"commit","task"));
 }
 @Test void replacementInvalidatesOperationGrant(){
  String grant=access.issue(request,"SyntheticPassword123","export","");request.getSession().setAttribute(SessionService.TOKEN,"b".repeat(43));assertThrows(Exception.class,()->access.consume(request,grant,"export",""));
 }
 @Test void expiredGrantRejected()throws Exception{
  String token=access.issue(request,"SyntheticPassword123","export","");var field=DatabaseAccess.class.getDeclaredField("grants");field.setAccessible(true);
  @SuppressWarnings("unchecked") var map=(Map<String,DatabaseAccess.Grant>)field.get(access);var g=map.get(token);map.put(token,new DatabaseAccess.Grant(g.user(),g.token(),g.action(),g.object(),0));assertThrows(Exception.class,()->access.consume(request,token,"export",""));
 }
 @Test void managerAndRemoteHttpRejectedEvenWithExistingSession(){
  request.getSession().setAttribute("user",new SessionUser(2L,"260002","虚构经理",1,1L,"测试"));assertThrows(Exception.class,()->access.user(request));
  request.getSession().setAttribute("user",executive);request.setRemoteAddr("192.168.1.20");assertThrows(Exception.class,()->access.user(request));
  request.setSecure(true);assertEquals(1L,access.user(request).id());
 }
 @Test void forwardedPlaintextRequestIsNotLocalAuthorization(){
  request.addHeader("X-Forwarded-For","192.168.1.20");assertThrows(Exception.class,()->access.user(request));
 }
 @Test void repeatedBadReauthenticationIsThrottled(){
  when(auth.login(anyString(),anyString())).thenThrow(new IllegalArgumentException("bad credentials"));
  for(int i=0;i<5;i++)assertThrows(Exception.class,()->access.issue(request,"wrong","export",""));
  var exception=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->access.issue(request,"SyntheticPassword123","export",""));assertEquals(429,exception.getStatusCode().value());
 }
}
