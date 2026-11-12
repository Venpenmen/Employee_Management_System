package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.AttendanceService;
import com.example.ems.service.DepartmentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AttendanceActionItemTest {
    private static final SessionUser EMPLOYEE = new SessionUser(1L,"000001","Fixture",0,2L,"Synthetic");

    @Test void returnsOnlyServerActionsAndRequiresSession() throws Exception {
        var attendance = mock(AttendanceService.class);
        when(attendance.punchOptions(EMPLOYEE)).thenReturn(new AttendanceService.PunchOptions(java.time.LocalDate.of(2026,11,6),"WARNING","CLOSED"));
        when(attendance.personalActionRequests(EMPLOYEE))
                .thenReturn(List.of(Map.of("id",91L,"status","APPROVED","direction","IN")));
        var mvc = MockMvcBuilders.standaloneSetup(new HomeActionItemController(
                List.of(new AttendanceActionItemProvider(attendance)))).build();
        mvc.perform(get("/api/home/action-items")).andExpect(status().isUnauthorized());
        verifyNoInteractions(attendance);

        var session = new MockHttpSession();
        session.setAttribute("user",EMPLOYEE);
        mvc.perform(get("/api/home/action-items").session(session))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("ATTENDANCE_IN"))
                .andExpect(jsonPath("$[1].href").value("/attendance/my?requestId=91#attendance-requests"));

        when(attendance.punchOptions(EMPLOYEE)).thenReturn(new AttendanceService.PunchOptions(java.time.LocalDate.of(2026,11,6),"CLOSED","CLOSED"));
        when(attendance.personalActionRequests(EMPLOYEE)).thenReturn(List.of());
        mvc.perform(get("/api/home/action-items").session(session)).andExpect(jsonPath("$.length()").value(0));
        verify(attendance,never()).readResult(any(),anyLong());
    }

    @Test void missingCheckInAppearsAsAbnormalCheckoutReminder() {
        var attendance = mock(AttendanceService.class);
        when(attendance.punchOptions(EMPLOYEE)).thenReturn(new AttendanceService.PunchOptions(
                java.time.LocalDate.of(2026,11,6),"CLOSED","EXCEPTION",true));
        when(attendance.personalActionRequests(EMPLOYEE)).thenReturn(List.of());
        var items = new AttendanceActionItemProvider(attendance).items(EMPLOYEE);
        org.junit.jupiter.api.Assertions.assertEquals(1,items.size());
        org.junit.jupiter.api.Assertions.assertEquals("今日未签到，可申请异常签退",items.get(0).message());
    }

    @Test void protectsAttendanceMutationsWithCsrf() throws Exception {
        var attendance = mock(AttendanceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AttendanceController(attendance,mock(DepartmentService.class)))
                .addInterceptors(new ProfileCsrfInterceptor()).build();
        var session = new MockHttpSession();
        session.setAttribute("user",EMPLOYEE);
        for (String path : List.of("/attendance/check-in","/attendance/check-out",
                "/attendance/requests/91/review","/attendance/requests/91/read","/attendance/calendar")) {
            mvc.perform(post(path).session(session)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(attendance);
        mvc.perform(post("/attendance/requests/91/read").session(session)
                .param("_csrf",(String)session.getAttribute("profileCsrf")))
                .andExpect(redirectedUrl("/attendance/my?requestId=91"));
        verify(attendance).readResult(EMPLOYEE,91L);
    }
}
