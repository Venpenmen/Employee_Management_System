package com.example.ems.upgrade;
import com.example.ems.model.SessionUser;
import com.example.ems.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkModeCompatibilityTest {
 @Test void compatibilityAllowsFrpButStillRequiresExecutive() throws Exception {
  SessionService sessions=mock(SessionService.class);
  AuthService auth=mock(AuthService.class);
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  PlatformTransactionManager tm=mock(PlatformTransactionManager.class);
  when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  DatabaseAccess access=new DatabaseAccess(sessions,auth,jdbc,tm);
  var field=DatabaseAccess.class.getDeclaredField("testCompatibilityMode");
  field.setAccessible(true);field.setBoolean(access,true);
  MockHttpServletRequest req=new MockHttpServletRequest();
  req.setRemoteAddr("198.51.100.20");req.setServerName("www.u3089790.nyat.app");
  req.addHeader("X-Forwarded-For","203.0.113.10");
  req.addHeader("X-Forwarded-Proto","http");
  MockHttpSession session=new MockHttpSession();req.setSession(session);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->access.user(req));
  session.setAttribute("user",new SessionUser(1L,"260001","test",2,null,""));
  assertEquals(1L,access.user(req).id());
 }
}
