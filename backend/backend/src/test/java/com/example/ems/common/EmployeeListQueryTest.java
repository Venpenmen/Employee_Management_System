package com.example.ems.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmployeeListQueryTest {
    @Test
    void returnParametersAreValidatedBeforeReuse() {
        EmployeeListQuery q = EmployeeListQuery.fromReturnParams("  张  ", "abc", "9", "-1", "37");
        assertEquals("张", q.getKeyword());
        assertNull(q.getDepartmentId());
        assertNull(q.getStatus());
        assertEquals(1, q.getPage());
        assertEquals(20, q.getPageSize());
        assertEquals("/employees?keyword=%E5%BC%A0", q.url());
    }

    @Test
    void keepsValidFiltersAndPage() {
        EmployeeListQuery q = EmployeeListQuery.fromReturnParams("", "2", "1", "3", "50");
        assertEquals("/employees?departmentId=2&status=1&page=3&pageSize=50", q.url());
    }

    @Test
    void urlCannotBeTurnedIntoAnotherLocation() {
        String url = EmployeeListQuery.fromReturnParams("a&b={x}#//evil", null, null, null, null).url();
        assertTrue(url.startsWith("/employees?keyword="));
        assertFalse(url.contains("{"));
        assertFalse(url.contains("#"));
        assertFalse(url.contains("&b="));
    }

    @Test
    void defaultsProduceBareListUrl() {
        assertEquals("/employees", EmployeeListQuery.defaults().url());
        assertNull(new EmployeeListQuery(null, 5L, 1, 2, 50).withoutDepartment().getDepartmentId());
    }
}
