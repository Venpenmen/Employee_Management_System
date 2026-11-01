package com.example.ems.web;
import com.example.ems.config.UpgradeIsolation;
import com.example.ems.service.*;
import com.example.ems.model.SessionUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NextSecurityTest {
 @Test void logoutInvalidatesEvenWhenDatabaseFails(){
  var sessions=mock(SessionService.class);var session=new MockHttpSession();
  session.setAttribute("user",new SessionUser(1L,"260001","Demo",2,null,null));session.setAttribute(SessionService.TOKEN,"token");
  doThrow(new IllegalStateException("unavailable")).when(sessions).revoke(1L,"token");
  assertThrows(IllegalStateException.class,()->new AuthController(mock(AuthService.class),sessions).logout(session));
  assertTrue(session.isInvalid());
 }
 @Test void wrongModeRejectedBeforeConnection(){
  var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:16606/ems_next_server_v121");
  assertThrows(IllegalStateException.class,()->new UpgradeIsolation().postProcessEnvironment(env,null));
  env.setActiveProfiles("server");
  // Matching schema alone cannot authorize connecting to another running RC2 instance.
  env.withProperty("server.servlet.session.cookie.name","EMS121N_COMPLETE_SERVER");
  var denied=assertThrows(IllegalStateException.class,()->new UpgradeIsolation().postProcessEnvironment(env,null));
  assertTrue(denied.getMessage().contains("no connection attempted"));
 }
 @Test void historicalDatasourceRejected(){
  var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:16506/ems_up_server_v120");env.setActiveProfiles("server");
  assertThrows(IllegalStateException.class,()->new UpgradeIsolation().postProcessEnvironment(env,null));
 }

 @Test void transactionalTokenValidationUsesCurrentRead(){
  var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);String token="a".repeat(43);
  when(jdbc.queryForList("SELECT token_hash FROM account_active_session WHERE employee_id=? FOR UPDATE",1L)).thenReturn(java.util.List.of(java.util.Map.of("token_hash",SessionService.digest(token))));
  org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
  try{assertEquals(SessionService.Validity.VALID,new SessionService(jdbc).validate(1L,token));}
  finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
  verify(jdbc).queryForList("SELECT token_hash FROM account_active_session WHERE employee_id=? FOR UPDATE",1L);
 }
 @Test void oversizedMultibyteRegistrationRejectedBeforeDatabase(){
  var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var service=new AuthService(jdbc,mock(LogService.class));
  assertThrows(IllegalArgumentException.class,()->service.register("260001","Demo","123456","13800000001","A1"+"中".repeat(24)));
  verifyNoInteractions(jdbc);
 }

 @Test void maintenanceAndSetupUrlsRejectHistoricalAndRemoteTargets(){
  assertThrows(IllegalArgumentException.class,()->com.example.ems.upgrade.Schema.safeUrl("jdbc:mysql://127.0.0.1:16506/ems_up_dev_v120"));
  assertThrows(IllegalArgumentException.class,()->com.example.ems.upgrade.Schema.safeUrl("jdbc:mysql://192.168.1.2:16606/ems_next_dev_v121"));
  assertDoesNotThrow(()->com.example.ems.upgrade.Schema.safeUrl("jdbc:mysql://127.0.0.1:16608/ems_next_dev_v121"));
 }

 @Test void staleObservedRequestCannotInvalidateUpdatedSameCookieSession(){
  var session=new MockHttpSession();var user=new SessionUser(1L,"260001","Demo",2,null,null);session.setAttribute("user",user);session.setAttribute(SessionService.TOKEN,"new-token");
  SessionService.invalidateObserved(session,1L,"old-token");assertFalse(session.isInvalid());assertEquals("new-token",SessionService.token(session));
  SessionService.invalidateObserved(session,1L,"new-token");assertTrue(session.isInvalid());assertNull(SessionService.token(session));
 }

 @Test void forbiddenPageStillKeepsSessionCsrfForSharedNavigation(){
  var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var sessions=mock(SessionService.class);var session=new MockHttpSession();
  session.setAttribute("user",new SessionUser(5L,"260005","Demo",0,1L,"A"));session.setAttribute(SessionService.TOKEN,"a".repeat(43));session.setAttribute("profileCsrf","csrf-kept");
  when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("FROM employee e JOIN user_account"),org.mockito.ArgumentMatchers.eq(5L))).thenReturn(java.util.List.of(java.util.Map.of("id",5L,"employee_no","260005","name","Demo","user_type",0,"department_id",1L,"department_name","A","employment_status",1,"account_status",1)));
  when(sessions.validate(5L,"a".repeat(43))).thenReturn(SessionService.Validity.VALID);
  var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/admin/departments");request.setSession(session);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new AuthInterceptor(jdbc,sessions).preHandle(request,new org.springframework.mock.web.MockHttpServletResponse(),new Object()));
  assertEquals("csrf-kept",request.getAttribute("profileCsrf"));assertFalse(session.isInvalid());
 }

 @Test void protectedWriteCannotInheritNewSameCookieCredentials(){
  var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var service=new SessionService(jdbc);var request=new org.springframework.mock.web.MockHttpServletRequest();var session=new MockHttpSession();
  session.setAttribute("user",new SessionUser(1L,"260001","Demo",2,null,null));session.setAttribute(SessionService.TOKEN,"new-token");request.setSession(session);request.setAttribute("ems.auth.observedUserId",1L);request.setAttribute("ems.auth.observedToken","old-token");
  org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
  try{assertThrows(SessionService.SessionExpired.class,()->service.requireObserved(1L));verifyNoInteractions(jdbc);}finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
 }

 @Test void startupGateRejectsRequestsUntilAllRunnersComplete() throws Exception {
  var gate=new com.example.ems.config.ApplicationReadiness();var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/login");var response=new org.springframework.mock.web.MockHttpServletResponse();
  assertFalse(gate.preHandle(request,response,new Object()));assertEquals(503,response.getStatus());gate.ready();assertTrue(gate.preHandle(request,new org.springframework.mock.web.MockHttpServletResponse(),new Object()));gate.closed();assertFalse(gate.isReady());
 }
}
