package com.example.ems.web;

import com.example.ems.common.ConflictException;
import com.example.ems.common.ForbiddenException;
import com.example.ems.common.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ErrorHandlingTest {
    @Controller
    static class Failing {
        @GetMapping("/bad") String bad() { throw new IllegalArgumentException("参数不对"); }
        @GetMapping("/forbidden") String forbidden() { throw new ForbiddenException("无权访问"); }
        @GetMapping("/missing") String missing() { throw new NotFoundException("不存在"); }
        @GetMapping("/conflict") String conflict() { throw new ConflictException("冲突"); }
        @GetMapping("/duplicate") String duplicate() { throw new org.springframework.dao.DuplicateKeyException("Duplicate entry private-value for key uk_private"); }
        @GetMapping("/boom") String boom() { throw new DataAccessResourceFailureException("Communications link failure at 10.0.0.5"); }
        @GetMapping("/number") String number(@RequestParam int page) { return "ok"; }
        @ResponseBody @GetMapping("/api/boom") String apiBoom() { throw new IllegalStateException("NullPointer deep inside"); }
        @ResponseBody @GetMapping("/api/missing") String apiMissing() { throw new NotFoundException("员工不存在"); }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Failing())
            .setControllerAdvice(new GlobalExceptionHandler(new ErrorResponses()))
            .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
            .build();

    @Test
    void distinguishesCommonStatuses() throws Exception {
        mvc.perform(get("/bad")).andExpect(status().isBadRequest()).andExpect(model().attribute("message", "参数不对"));
        mvc.perform(get("/forbidden")).andExpect(status().isForbidden());
        mvc.perform(get("/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/conflict")).andExpect(status().isConflict());
        mvc.perform(get("/number").param("page", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/number")).andExpect(status().isBadRequest());
        mvc.perform(post("/bad")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void duplicateWritesAreSafeConflicts() throws Exception {
        mvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(model().attribute("message", "数据已存在，请刷新后重试"))
                .andExpect(model().attribute("message", not(containsString("private-value"))));
    }

    @Test
    void unexpectedErrorsShowOnlyAnErrorId() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error-page"))
                .andExpect(model().attribute("message", containsString("错误编号")))
                .andExpect(model().attribute("message", not(containsString("10.0.0.5"))));
    }

    @Test
    void apiErrorsAreJson() throws Exception {
        mvc.perform(get("/api/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg", containsString("错误编号")))
                .andExpect(jsonPath("$.msg", not(containsString("NullPointer"))));
        mvc.perform(get("/api/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.msg").value("员工不存在"));
    }
}
