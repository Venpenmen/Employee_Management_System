package com.example.ems.profile;

import com.example.ems.common.ConflictException;
import com.example.ems.common.NotFoundException;
import com.example.ems.model.SessionUser;
import com.example.ems.service.EmployeeService;
import com.example.ems.web.ErrorResponses;
import com.example.ems.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Errors;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileControllerTest {
    private ProfileService profiles;
    private MockMvc mvc;
    private MockHttpSession session;
    private String token;

    @BeforeEach
    void setUp() {
        profiles = mock(ProfileService.class);
        ProfileCsrf csrf = new ProfileCsrf();
        mvc = MockMvcBuilders.standaloneSetup(new ProfileController(profiles, mock(EmployeeService.class), csrf))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorResponses()))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        session = new MockHttpSession();
        session.setAttribute("user", new SessionUser(7L, "260005", "赵一", 0, 1L, "A部门"));
        token = csrf.token(session);
    }

    @Test
    void missingCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/profile").session(session).param("version", "0").param("phone", "13912345678"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/profile/skills/3/delete").session(session))
                .andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }

    @Test
    void savesOnlyForTheLoggedInOwnerAndIgnoresProtectedFields() throws Exception {
        when(profiles.saveBasic(anyLong(), any(BasicProfileForm.class), any(Errors.class))).thenReturn(List.of("手机号"));
        mvc.perform(post("/profile").session(session).param("_csrf", token)
                        .param("version", "4").param("phone", "13912345678")
                        .param("employeeId", "1").param("userType", "2").param("departmentId", "9").param("basicSalary", "99999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile#contact"));
        ArgumentCaptor<BasicProfileForm> form = ArgumentCaptor.forClass(BasicProfileForm.class);
        verify(profiles).saveBasic(eq(7L), form.capture(), any(Errors.class));
        assertEquals("13912345678", form.getValue().getPhone());
        assertEquals(4, form.getValue().getVersion());
    }

    @Test
    void staleFormReturnsConflictAndKeepsInput() throws Exception {
        when(profiles.saveBasic(anyLong(), any(BasicProfileForm.class), any(Errors.class)))
                .thenThrow(new ConflictException("资料已在其他页面保存"));
        mvc.perform(post("/profile").session(session).param("_csrf", token).param("version", "1").param("phone", "13912345678"))
                .andExpect(status().isConflict())
                .andExpect(view().name("profile-edit"))
                .andExpect(model().attributeHasErrors("form"))
                .andExpect(model().attribute("form", hasProperty("phone", "13912345678")));
    }

    @Test
    void validationErrorsKeepEntryFormValues() throws Exception {
        doAnswer(inv -> { inv.<Errors>getArgument(3).rejectValue("endMonth", "range", "结束月份不能早于开始月份"); return null; })
                .when(profiles).createEntry(eq(ProfileSection.EXPERIENCE), eq(7L), any(ProfileEntryForm.class), any(Errors.class));
        mvc.perform(post("/profile/experience").session(session).param("_csrf", token)
                        .param("company", "某公司").param("startMonth", "2023-05").param("endMonth", "2021-01"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("profile-entry"))
                .andExpect(model().attribute("kind", "experience"))
                .andExpect(model().attributeHasFieldErrors("form", "endMonth"));
    }

    @Test
    void anotherPersonsEntryIsNotFound() throws Exception {
        when(profiles.entryForm(ProfileSection.SKILLS, 99L, 7L)).thenThrow(new NotFoundException("档案记录不存在"));
        mvc.perform(get("/profile/skills/99/edit").session(session)).andExpect(status().isNotFound());
        doThrow(new NotFoundException("档案记录不存在")).when(profiles).deleteEntry(ProfileSection.SKILLS, 99L, 7L);
        mvc.perform(post("/profile/skills/99/delete").session(session).param("_csrf", token)).andExpect(status().isNotFound());
    }

    @Test
    void unknownSectionIsNotFound() throws Exception {
        mvc.perform(get("/profile/salary/new").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/profile/skills/abc/edit").session(session)).andExpect(status().isBadRequest());
    }

    private static org.hamcrest.Matcher<Object> hasProperty(String name, Object value) {
        return org.hamcrest.Matchers.hasProperty(name, org.hamcrest.Matchers.equalTo(value));
    }
}
