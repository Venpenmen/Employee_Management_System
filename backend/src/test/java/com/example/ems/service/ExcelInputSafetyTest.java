package com.example.ems.service;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.util.zip.*;
class ExcelInputSafetyTest {
 private final ExcelService service=new ExcelService(mock(org.springframework.jdbc.core.JdbcTemplate.class),mock(EmployeeService.class));
 @Test void acceptsPlainWorkbookAndPreservesRecruitmentValues() throws Exception{
  var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var employees=mock(EmployeeService.class);
  when(employees.recruit(anyString(),anyString(),any(),any(),isNull(),isNull(),eq(0),eq(1L))).thenReturn("260005");
  var bytes=new ByteArrayOutputStream();
  try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){
   var sheet=workbook.createSheet("员工");sheet.createRow(0).createCell(0).setCellValue("姓名");var row=sheet.createRow(1);
   String[] values={"测试员工","110101199001019995","1990-01-01","2020-01-01","",""};
   for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);
   workbook.write(bytes);
  }
  var result=new ExcelService(jdbc,employees).importEmployees(new MockMultipartFile("file","employees.xlsx","",bytes.toByteArray()),1L);
  assertEquals(1,result.success(),result.errors().toString());assertEquals(java.util.List.of("260005"),result.employeeNos());
  verify(employees).recruit("测试员工","110101199001019995",java.time.LocalDate.of(1990,1,1),java.time.LocalDate.of(2020,1,1),null,null,0,1L);
 }
 @Test void rejectsMacrosDisguisedContentAndOversizedFiles() throws Exception{
  assertThrows(IllegalArgumentException.class,()->service.importEmployees(new MockMultipartFile("file","bad.xlsm","",new byte[]{1}),1L));
  assertThrows(IllegalArgumentException.class,()->service.importEmployees(new MockMultipartFile("file","bad.xlsx","","not a workbook".getBytes()),1L));
  assertThrows(IllegalArgumentException.class,()->service.importEmployees(new MockMultipartFile("file","large.xlsx","",new byte[10*1024*1024+1]),1L));
  var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("xl/vbaProject.bin"));zip.write(new byte[]{1});zip.closeEntry();}
  assertThrows(IllegalArgumentException.class,()->service.importEmployees(new MockMultipartFile("file","hidden.xlsx","",out.toByteArray()),1L));
 }
}
