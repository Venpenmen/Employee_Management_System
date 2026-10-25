package com.example.ems.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {
    @Test
    void nonPositivePageBecomesFirstPage() {
        assertEquals(1, Pagination.of(0, 20, 100).getPage());
        assertEquals(1, Pagination.of(-5, 20, 100).getPage());
        assertEquals(1, Pagination.of(null, 20, 100).getPage());
    }

    @Test
    void supportsOnlyTwentyFiftyHundred() {
        assertEquals(20, Pagination.of(1, 20, 10).getPageSize());
        assertEquals(50, Pagination.of(1, 50, 10).getPageSize());
        assertEquals(100, Pagination.of(1, 100, 10).getPageSize());
        assertEquals(20, Pagination.of(1, 30, 10).getPageSize());
        assertEquals(20, Pagination.of(1, 100000, 10).getPageSize());
        assertEquals(20, Pagination.of(1, null, 10).getPageSize());
    }

    @Test
    void clampsToLastPageAndComputesOffset() {
        Pagination p = Pagination.of(9, 20, 45);
        assertEquals(3, p.getTotalPages());
        assertEquals(3, p.getPage());
        assertEquals(40, p.getOffset());
        assertEquals(45, p.getTotal());
    }

    @Test
    void emptyResultShowsPageOneOfOne() {
        Pagination p = Pagination.of(4, 50, 0);
        assertEquals(1, p.getPage());
        assertEquals(1, p.getTotalPages());
        assertEquals(0, p.getOffset());
    }
}
