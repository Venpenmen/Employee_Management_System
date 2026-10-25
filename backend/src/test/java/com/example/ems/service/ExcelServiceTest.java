package com.example.ems.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExcelServiceTest {
    private final EmployeeService employees = mock(EmployeeService.class);
    private final ExcelService excel = new ExcelService(mock(JdbcTemplate.class), employees);

    private MockMultipartFile workbook(boolean numeric, boolean invalid) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Synthetic");
            sheet.createRow(0).createCell(0).setCellValue("Name");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Synthetic employee");
            row.createCell(1).setCellValue("110101199901018888");
            for (int column : new int[]{2, 3}) {
                Cell cell = row.createCell(column);
                if (numeric) {
                    cell.setCellValue(LocalDate.of(1999, 1, 1).atStartOfDay());
                    CellStyle style = wb.createCellStyle();
                    style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
                    cell.setCellStyle(style);
                } else cell.setCellValue(invalid ? "not-a-date" : "1999-01-01");
            }
            wb.write(out);
            return new MockMultipartFile("file", "synthetic.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private void assertDates(boolean numeric) throws Exception {
        when(employees.recruit(anyString(), anyString(), any(), any(), isNull(), isNull(), eq(0), eq(1L))).thenReturn("990001");
        var result = excel.importEmployees(workbook(numeric, false), 1L);
        assertEquals(1, result.success());
        assertTrue(result.errors().isEmpty());
        verify(employees).recruit("Synthetic employee", "110101199901018888", LocalDate.of(1999, 1, 1), LocalDate.of(1999, 1, 1), null, null, 0, 1L);
    }

    @Test void acceptsIsoTextDates() throws Exception { assertDates(false); }
    @Test void preservesExcelNumericDates() throws Exception { assertDates(true); }
    @Test void invalidDateDoesNotRecruit() throws Exception {
        var result = excel.importEmployees(workbook(false, true), 1L);
        assertEquals(0, result.success());
        assertEquals(1, result.errors().size());
        verifyNoInteractions(employees);
    }
}
