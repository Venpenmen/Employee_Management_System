package com.example.ems.web;

import com.example.ems.model.SessionUser;
import com.example.ems.service.AttendanceService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import static org.junit.jupiter.api.Assertions.*;

class AttendanceTemplateTest {
    @Test void rendersPersonalAndManagementControls() {
        var context = context(2);
        context.setVariables(Map.of(
                "today",Map.of(),"month","2026-11","records",List.of(),"departments",List.of(),
                "summary",Map.of("normal_days",0,"late_count",0,"early_count",0,"missing_count",0,"leave_days",0),
                "rule",Map.of("work_start_time", LocalTime.of(9,0),"work_end_time",LocalTime.of(17,0),
                        "late_grace_minutes",5,"early_grace_minutes",5,"in_exception",30,"out_exception",30,
                        "earliest_in",LocalTime.of(8,0),"latest_out",LocalTime.of(20,0),
                        "last_in",LocalTime.of(9,35),"first_out",LocalTime.of(16,25)),
                "options",new AttendanceService.PunchOptions(LocalDate.of(2026,11,6),"EXCEPTION","CLOSED"),
                "requests", List.of(Map.of("id",11,"direction","IN","status","PENDING","reason","测试",
                        "punched_at",LocalDateTime.of(2026,11,6,9,10))),
                "reviews",List.of(Map.of("id",11,"direction","IN","name","测试","deviation_minutes",10,
                        "reason","测试","punched_at",LocalDateTime.of(2026,11,6,9,10))),
                "calendarExceptions",List.of()));
        var engine = engine();
        String personal = engine.process("attendance-my",context);
        assertTrue(personal.contains("提交异常签到"));
        assertTrue(personal.contains("disabled=\"disabled\""));
        assertTrue(personal.contains("待核实"));
        context.setVariable("options", new AttendanceService.PunchOptions(
                LocalDate.of(2026,11,6),"CLOSED","EXCEPTION",true));
        String missing = engine.process("attendance-my",context);
        assertTrue(missing.contains("今日尚未签到"));
        assertTrue(missing.contains("data-missing-check-in=\"true\""));
        String management = engine.process("attendance-manage",context);
        assertTrue(management.contains("/attendance/requests/11/review"));
        assertTrue(management.contains("最早签退 16:25"));
    }

    @Test void dashboardPlacesHiddenRemindersBetweenExistingSections() {
        var context = context(0);
        context.setVariables(Map.of("communicationUnread",0,"approverPendingCount",0,"employeeCount",1,
                "departmentCount",1,"pendingCount",0,"departments",List.of()));
        String page = engine().process("dashboard", context);
        int quick = page.indexOf("quick-links");
        int items = page.indexOf("id=\"home-action-items\"");
        int announcement = page.indexOf("公告中心");
        assertTrue(quick >= 0 && items > quick && announcement > items);
        assertTrue(page.substring(items,announcement).contains("hidden"));
    }

    private static WebContext context(int role) {
        var application = new GenericApplicationContext();
        application.registerBean("viewFormats",ViewFormats.class);
        application.refresh();
        var servlet = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(new MockHttpServletRequest(servlet),new MockHttpServletResponse());
        var context = new WebContext(exchange);
        context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(application,null));
        context.setVariable("currentUser",new SessionUser(1L,"000001","测试",role,2L,"测试部门"));
        context.setVariable("profileCsrf","fixture-csrf");
        return context;
    }

    private static SpringTemplateEngine engine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
