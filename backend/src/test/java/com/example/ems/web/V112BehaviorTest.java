package com.example.ems.web;
import com.example.ems.model.SessionUser;
import com.example.ems.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class V112BehaviorTest {
    SessionUser employee=new SessionUser(5L,"260005","员工",0,1L,"A");
    SessionUser manager=new SessionUser(2L,"260002","经理",1,1L,"A");
    SessionUser executive=new SessionUser(1L,"260001","高层",2,null,"未分配");
    @Test void currencyDoesNotChangeValueAndGreetingBoundaries(){
        var f=new ViewFormats();var amount=new BigDecimal("123456.7891");
        assertEquals("¥123,456.79",f.money(amount));assertEquals("123456.7891",amount.toPlainString());
        assertEquals("未设置",f.money(null));assertEquals("—",f.dateTime(null));
        assertEquals("2026-10-18",f.date(LocalDate.of(2026,10,18)));
        assertEquals("2026-10-18 09:07",f.dateTime(LocalDateTime.of(2026,10,18,9,7,59)));
        for(int h=0;h<24;h++)assertEquals(h<6||h>=18?"晚上好":h<12?"早上好":"下午好",f.greeting(LocalTime.of(h,0)));
    }
    @Test void privacyProjectionRemovesSalaryEvenFromSerializedData(){
        var row=Map.<String,Object>of("id",1,"department_id",2,"department_name","A","position_name","开发","status",1,"base_salary",9999,"position_salary",500,"is_manager_position",0);
        var rows=List.of(row);
        assertEquals(Set.of("id","department_name","status"),OptionViews.departments(rows,false).get(0).keySet());
        assertEquals(Set.of("id","department_id","position_name","is_manager_position","status"),OptionViews.positions(rows,false).get(0).keySet());
        assertFalse(OptionViews.dashboard(rows,false).get(0).containsKey("base_salary"));
        assertEquals(row,OptionViews.positions(rows,true).get(0));assertTrue(row.containsKey("base_salary"));
    }
    @Test void paginationSharesScopeAndClampsPageAndSize(){
        for(var user:List.of(employee,manager,executive)){
            JdbcTemplate jdbc=mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(41L);
            when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
            var result=new EmployeeService(jdbc,mock(LogService.class)).page(user,"  测试  ",99L,1,999,7);
            assertEquals(3,result.page());assertEquals(20,result.pageSize());assertEquals(41,result.total());
            var sql=org.mockito.ArgumentCaptor.forClass(String.class);var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).queryForObject(sql.capture(),eq(Long.class),args.capture());
            String countWhere=sql.getValue().substring(sql.getValue().indexOf(" WHERE"));
            var pageSql=org.mockito.ArgumentCaptor.forClass(String.class);var pageArgs=org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).queryForList(pageSql.capture(),pageArgs.capture());
            assertTrue(pageSql.getValue().contains(countWhere+" ORDER BY"));
            assertArrayEquals(args.getValue(),Arrays.copyOf(pageArgs.getValue(),args.getValue().length));
            assertEquals(user.isExecutive()?99L:1L,args.getValue()[user.isExecutive()?2:0]);
            if(!user.isExecutive())assertFalse(pageSql.getValue().contains("basic_salary"));
        }
    }
    @Test void emptyPageAndNonpositivePageAreOne(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        var service=new EmployeeService(jdbc,mock(LogService.class));
        var page=service.page(employee,null,null,null,-9,100);
        assertEquals(1,page.page());assertEquals(1,page.totalPages());assertEquals(100,page.pageSize());
    }
    @Test void employeeReturnOnlyBuildsInternalEncodedUrl(){
        var r=new MockHttpServletRequest();r.setParameter("returnKeyword"," A&status=3 ");r.setParameter("returnPage","-1");r.setParameter("returnPageSize","3");r.setParameter("returnUrl","https://evil.example");
        var context=EmployeeReturn.from(r);
        assertEquals(1,context.page());assertEquals(20,context.pageSize());
        assertTrue(context.url().startsWith("/employees?"));assertTrue(context.url().contains("%26"));assertFalse(context.url().contains("evil"));
        r.setParameter("returnPage","abc");assertThrows(ResponseStatusException.class,()->EmployeeReturn.from(r));
    }
    @Test void nonnumericPaginationProducesReadable400() throws Exception {
        var session=new MockHttpSession();session.setAttribute("user",executive);
        var mvc=MockMvcBuilders.standaloneSetup(new EmployeeController(mock(EmployeeService.class),mock(DepartmentService.class),mock(ProfileService.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/employees").session(session).param("page","abc")).andExpect(status().isBadRequest()).andExpect(view().name("error-page")).andExpect(model().attribute("message","查询参数格式不正确，请检查页码、每页条数和筛选条件"));
        mvc.perform(get("/employees").session(session).param("pageSize","abc")).andExpect(status().isBadRequest());
    }
    @Test void successfulLoginRotatesOnlySuccessfulSession() throws Exception {
        var auth=mock(AuthService.class);when(auth.login("260005"," Password123 ")).thenReturn(employee);
        when(auth.login("260005","wrong")).thenThrow(new IllegalArgumentException("工号或密码错误"));
        var mvc=MockMvcBuilders.standaloneSetup(new AuthController(auth,mock(com.example.ems.service.SessionService.class))).setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver("/views/",".html")).build();
        var session=new MockHttpSession();String before=session.getId();
        mvc.perform(post("/login").session(session).param("employeeNo","260005").param("password"," Password123 ")).andExpect(redirectedUrl("/dashboard"));
        assertNotEquals(before,session.getId());assertEquals(employee,session.getAttribute("user"));
        String valid=session.getId();
        mvc.perform(post("/login").session(session).param("employeeNo","260005").param("password","wrong")).andExpect(view().name("login"));
        assertEquals(valid,session.getId());
    }
    @Test void anonymousApi401AndPageRedirect() throws Exception {
        var interceptor=new AuthInterceptor(mock(JdbcTemplate.class),mock(com.example.ems.service.SessionService.class));
        for(String path:List.of("/api/me","/api/dictionaries/employee-options")){
            var request=new MockHttpServletRequest("GET",path);var response=new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request,response,new Object()));assertEquals(401,response.getStatus());
            assertTrue(response.getContentAsString().contains("\"data\":null"));assertNull(response.getRedirectedUrl());
        }
        var response=new MockHttpServletResponse();interceptor.preHandle(new MockHttpServletRequest("GET","/profile"),response,new Object());
        assertEquals("/login",response.getRedirectedUrl());
    }
    @RestController static class Errors {
        @GetMapping({"/api/test-error/{status}","/test-error/{status}"})
        Object fail(@PathVariable int status){
            if(status==500)throw new DataAccessResourceFailureException("jdbc:mysql://secret/password SQL SELECT * stack");
            if(status==409)throw new DataIntegrityViolationException("SQL duplicate secret");
            throw new ResponseStatusException(HttpStatus.valueOf(status),"可读错误");
        }
    }
    @Test void apiAndBrowserErrorsHaveCorrectStatusAndSafeMessage() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new Errors()).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(int code:new int[]{400,403,404,409,500}){
            var result=mvc.perform(get("/api/test-error/"+code)).andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.data").doesNotExist()).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains("secret"));
            mvc.perform(get("/test-error/"+code)).andExpect(status().is(code)).andExpect(view().name("error-page"));
            if(code==500)assertTrue(result.getResponse().getContentAsString().contains("错误编号"));
        }
    }
}
