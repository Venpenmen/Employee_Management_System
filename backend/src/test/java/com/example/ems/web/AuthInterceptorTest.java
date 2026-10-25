package com.example.ems.web;

import com.example.ems.common.ForbiddenException;
import com.example.ems.model.SessionUser;
import com.example.ems.testsupport.FakeJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AuthInterceptorTest {
    private final FakeJdbc jdbc = new FakeJdbc();
    private final AuthInterceptor interceptor = new AuthInterceptor(jdbc);

    private MockHttpServletRequest request(String path, SessionUser user, int userType) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (user != null) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("user", user);
            request.setSession(session);
            Map<String, Object> row = new HashMap<>();
            row.put("id", user.id()); row.put("employee_no", user.employeeNo()); row.put("name", user.name());
            row.put("user_type", userType); row.put("department_id", 1L); row.put("department_name", "A部门");
            row.put("employment_status", 1); row.put("account_status", 1);
            jdbc.onQueryForList = (sql, args) -> new ArrayList<>(List.of(row));
        }
        return request;
    }

    @Test
    void anonymousApiCallGets401Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("/api/me", null, 0), response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"code\":401"));
    }

    @Test
    void anonymousPageRedirectsToLogin() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("/dashboard", null, 0), response, new Object()));
        assertEquals("/login", response.getRedirectedUrl());
    }

    @Test
    void employeeCannotOpenExecutiveOrApprovalPages() {
        SessionUser employee = new SessionUser(5L, "260005", "赵一", 0, 1L, "A部门");
        assertThrows(ForbiddenException.class, () -> interceptor.preHandle(request("/admin/salaries", employee, 0), new MockHttpServletResponse(), new Object()));
        assertThrows(ForbiddenException.class, () -> interceptor.preHandle(request("/leaves/approvals", employee, 0), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void managerCanApproveButNotAdminister() throws Exception {
        SessionUser manager = new SessionUser(2L, "260002", "张经理", 1, 1L, "A部门");
        assertTrue(interceptor.preHandle(request("/leaves/approvals", manager, 1), new MockHttpServletResponse(), new Object()));
        assertThrows(ForbiddenException.class, () -> interceptor.preHandle(request("/admin/logs", manager, 1), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void sessionUserIsRefreshedFromDatabase() throws Exception {
        // 会话里仍是经理，但数据库中已被撤职为普通员工
        SessionUser stale = new SessionUser(2L, "260002", "张经理", 1, 1L, "A部门");
        MockHttpServletRequest request = request("/leaves/approvals", stale, 0);
        assertThrows(ForbiddenException.class, () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(0, ((SessionUser) request.getSession().getAttribute("user")).userType());
    }
}
