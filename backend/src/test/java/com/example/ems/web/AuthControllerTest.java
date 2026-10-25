package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerTest {
    private final AuthService auth = mock(AuthService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth))
            .setControllerAdvice(new GlobalExceptionHandler(new ErrorResponses()))
            .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
            .build();

    @Test
    void successfulLoginRotatesSessionId() throws Exception {
        SessionUser user = new SessionUser(1L, "260001", "系统高层", 2, null, "未分配");
        when(auth.login("260001", "Admin123")).thenReturn(user);
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        MvcResult result = mvc.perform(post("/login").session(session).param("employeeNo", "260001").param("password", "Admin123"))
                .andExpect(redirectedUrl("/dashboard")).andReturn();
        String after = result.getRequest().getSession().getId();
        assertNotEquals(before, after);
        assertEquals(user, result.getRequest().getSession().getAttribute("user"));
    }

    @Test
    void failedLoginKeepsSessionAndShowsMessage() throws Exception {
        when(auth.login("260001", "wrong")).thenThrow(new IllegalArgumentException("工号或密码错误"));
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        MvcResult result = mvc.perform(post("/login").session(session).param("employeeNo", "260001").param("password", "wrong"))
                .andExpect(view().name("login"))
                .andExpect(model().attribute("error", "工号或密码错误")).andReturn();
        assertEquals(before, result.getRequest().getSession().getId());
        assertNull(result.getRequest().getSession().getAttribute("user"));
    }

    @Test
    void unexpectedLoginFailureIsServerErrorWithoutDetails() throws Exception {
        when(auth.login("260001", "x")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("jdbc:mysql://secret-host refused"));
        mvc.perform(post("/login").param("employeeNo", "260001").param("password", "x"))
                .andExpect(status().isInternalServerError())
                .andExpect(model().attribute("message", org.hamcrest.Matchers.containsString("错误编号")))
                .andExpect(model().attribute("message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-host"))));
    }
}
