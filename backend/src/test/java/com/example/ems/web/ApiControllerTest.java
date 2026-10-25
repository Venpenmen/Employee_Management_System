package com.example.ems.web;

import com.example.ems.model.SessionUser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ApiControllerTest {
    private static List<Map<String, Object>> rows() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L); row.put("department_name", "A部门");
        row.put("base_salary", new BigDecimal("5000")); row.put("position_salary", new BigDecimal("1200"));
        return List.of(row);
    }

    @Test
    void employeesAndManagersDoNotReceiveSalaryFields() {
        for (int type : new int[]{0, 1}) {
            List<Map<String, Object>> projected = ApiController.project(rows(), new SessionUser(5L, "260005", "赵一", type, 1L, "A部门"));
            assertFalse(projected.get(0).containsKey("base_salary"));
            assertFalse(projected.get(0).containsKey("position_salary"));
            assertEquals("A部门", projected.get(0).get("department_name"));
        }
    }

    @Test
    void executivesKeepSalaryFields() {
        List<Map<String, Object>> projected = ApiController.project(rows(), new SessionUser(1L, "260001", "系统高层", 2, null, "未分配"));
        assertTrue(projected.get(0).containsKey("base_salary"));
    }
}
