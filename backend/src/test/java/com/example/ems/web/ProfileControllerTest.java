package com.example.ems.web;

import com.example.ems.model.ProfileForms;
import com.example.ems.model.SessionUser;
import com.example.ems.service.EmployeeService;
import com.example.ems.service.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileControllerTest {
    private final ProfileService profiles=mock(ProfileService.class);
    private final EmployeeService employees=mock(EmployeeService.class);
    private final SessionUser user=new SessionUser(5L,"260005","测试员工",0,1L,"A部门");
    private final MockHttpSession session=new MockHttpSession();
    private MockMvc mvc;
    @BeforeEach void setup(){
        session.setAttribute("user",user);session.setAttribute("profileCsrf","test-token");
        mvc=MockMvcBuilders.standaloneSetup(new ProfileController(profiles,employees))
            .addInterceptors(new ProfileCsrfInterceptor()).build();
    }
    @Test void rejectsMissingOrWrongCsrf() throws Exception {
        mvc.perform(post("/profile").session(session).param("phone","13900000001").param("version","0")).andExpect(status().isForbidden());
        mvc.perform(post("/profile/skills/1/delete").session(session).param("_csrf","wrong")).andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }
    @Test void bindsOnlyEditableFieldsAndAlwaysUsesSessionOwner() throws Exception {
        mvc.perform(post("/profile").session(session).param("_csrf","test-token").param("phone","13900000001")
            .param("version","0").param("email","owner@example.com").param("gender","")
            .param("employeeId","999").param("userType","2").param("departmentId","88").param("basicSalary","999999"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/profile"));
        var captor=ArgumentCaptor.forClass(ProfileForms.Basic.class);
        verify(profiles).saveBasic(captor.capture(),eq(user));
        assertEquals("owner@example.com",captor.getValue().email());
        assertEquals("13900000001",captor.getValue().phone());
    }
    @Test void invalidFormRetainsSubmittedValues() throws Exception {
        mvc.perform(post("/profile").session(session).param("_csrf","test-token").param("phone","bad")
            .param("version","0").param("address","应保留的输入"))
            .andExpect(status().isOk()).andExpect(view().name("profile-edit"))
            .andExpect(model().attributeHasFieldErrors("form","phone"));
        verifyNoInteractions(profiles);
    }
    @Test void ownershipErrorsRemainHttp404() throws Exception {
        when(profiles.entryForm(ProfileService.Section.EDUCATION,99L,user))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不属于您"));
        mvc.perform(get("/profile/education/99/edit").session(session)).andExpect(status().isNotFound());
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不属于您"))
            .when(profiles).deleteEntry(ProfileService.Section.SKILLS,99L,user);
        mvc.perform(post("/profile/skills/99/delete").session(session).param("_csrf","test-token"))
            .andExpect(status().isNotFound());
    }
}
