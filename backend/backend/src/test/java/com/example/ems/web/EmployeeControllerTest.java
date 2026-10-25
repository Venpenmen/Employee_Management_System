package com.example.ems.web;

import com.example.ems.common.EmployeeListQuery;
import com.example.ems.common.PageResult;
import com.example.ems.common.Pagination;
import com.example.ems.model.SessionUser;
import com.example.ems.profile.ProfileService;
import com.example.ems.service.DepartmentService;
import com.example.ems.service.EmployeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmployeeControllerTest {
    private EmployeeService employees;
    private DepartmentService departments;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        employees = mock(EmployeeService.class);
        departments = mock(DepartmentService.class);
        when(departments.listDepartments()).thenAnswer(inv -> {
            Map<String, Object> d = new HashMap<>();
            d.put("id", 1L); d.put("department_name", "A部门"); d.put("base_salary", new BigDecimal("5000"));
            return new ArrayList<>(List.of(d));
        });
        when(departments.positionsByDepartment(anyLong())).thenAnswer(inv -> {
            Map<String, Object> p = new HashMap<>();
            p.put("id", 3L); p.put("position_name", "Aa"); p.put("position_salary", new BigDecimal("1200")); p.put("is_manager_position", 0);
            return new ArrayList<>(List.of(p));
        });
        when(employees.page(any(), any())).thenAnswer(inv -> {
            EmployeeListQuery q = inv.getArgument(1);
            return new PageResult(new ArrayList<>(), Pagination.of(q.getPage(), q.getPageSize(), 45));
        });
        mvc = MockMvcBuilders.standaloneSetup(new EmployeeController(employees, departments, mock(ProfileService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorResponses()))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    private static MockHttpSession as(int userType) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", new SessionUser(9L, "260009", "测试", userType, 1L, "A部门"));
        return session;
    }

    @Test
    void forgedDepartmentFilterIsIgnoredForManagers() throws Exception {
        mvc.perform(get("/employees").session(as(1)).param("departmentId", "2").param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("departmentId", org.hamcrest.Matchers.nullValue()));
        ArgumentCaptor<EmployeeListQuery> query = ArgumentCaptor.forClass(EmployeeListQuery.class);
        verify(employees).page(any(), query.capture());
        assertNull(query.getValue().getDepartmentId());
        assertEquals(50, query.getValue().getPageSize());
    }

    @Test
    void outOfRangePageIsClampedAndSalaryOptionsHidden() throws Exception {
        var result = mvc.perform(get("/employees").session(as(1)).param("page", "99")).andExpect(status().isOk()).andReturn();
        Pagination p = (Pagination) result.getModelAndView().getModel().get("pagination");
        assertEquals(3, p.getPage());
        EmployeeListQuery ctx = (EmployeeListQuery) result.getModelAndView().getModel().get("returnContext");
        assertEquals(3, ctx.getPage());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> depts = (List<Map<String, Object>>) result.getModelAndView().getModel().get("departments");
        assertFalse(depts.get(0).containsKey("base_salary"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> positions = (List<Map<String, Object>>) result.getModelAndView().getModel().get("positions");
        assertFalse(positions.get(0).containsKey("position_salary"));
    }

    @Test
    void nonNumericPagingParametersAreBadRequests() throws Exception {
        mvc.perform(get("/employees").session(as(2)).param("page", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/employees").session(as(2)).param("pageSize", "1e3")).andExpect(status().isBadRequest());
        mvc.perform(get("/employees").session(as(2)).param("status", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void actionsReturnToTheValidatedOriginalQuery() throws Exception {
        mvc.perform(post("/employees/260005/terminate").session(as(2)).param("reason", "合同到期")
                        .param("returnKeyword", "赵").param("returnStatus", "1").param("returnPage", "2")
                        .param("returnPageSize", "50").param("returnDepartmentId", "javascript:alert(1)"))
                .andExpect(redirectedUrl("/employees?keyword=%E8%B5%B5&status=1&page=2&pageSize=50"));
    }

    @Test
    void onlyExecutivesOpenOtherEmployeesArchive() throws Exception {
        mvc.perform(get("/employees/260005").session(as(1))).andExpect(status().isForbidden());
        verify(employees, never()).detail(anyString(), any());
    }
}
