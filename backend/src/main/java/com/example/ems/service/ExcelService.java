package com.example.ems.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ExcelService {
    private final JdbcTemplate jdbc;
    private final EmployeeService employeeService;
    public ExcelService(JdbcTemplate jdbc, EmployeeService employeeService) { this.jdbc = jdbc; this.employeeService = employeeService; }

    public record ImportResult(int total, int success, List<String> errors, List<String> employeeNos) {}

    public ImportResult importEmployees(MultipartFile file, Long operatorId) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择Excel文件");
        int total = 0, success = 0;
        List<String> errors = new ArrayList<>();
        List<String> employeeNos = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || fmt.formatCellValue(row.getCell(0)).isBlank()) continue;
                total++;
                try {
                    String name = fmt.formatCellValue(row.getCell(0)).trim();
                    String idNo = fmt.formatCellValue(row.getCell(1)).trim();
                    LocalDate birth = parseDate(row.getCell(2), fmt);
                    LocalDate hire = parseDate(row.getCell(3), fmt);
                    String deptName = fmt.formatCellValue(row.getCell(4)).trim();
                    String positionName = fmt.formatCellValue(row.getCell(5)).trim();
                    Long deptId = null, positionId = null;
                    if (!deptName.isBlank()) {
                        List<Map<String,Object>> depts = jdbc.queryForList("SELECT id FROM department WHERE department_name=?", deptName);
                        if (depts.isEmpty()) throw new IllegalArgumentException("部门不存在：" + deptName);
                        deptId = ((Number)depts.get(0).get("id")).longValue();
                        List<Map<String,Object>> positions = jdbc.queryForList("SELECT id FROM position_info WHERE department_id=? AND position_name=? AND status=1", deptId, positionName);
                        if (positions.isEmpty()) throw new IllegalArgumentException("职位不存在或不属于该部门：" + positionName);
                        positionId = ((Number)positions.get(0).get("id")).longValue();
                    }
                    employeeNos.add(employeeService.recruit(name,idNo,birth,hire,deptId,positionId,0,operatorId));
                    success++;
                } catch (Exception ex) { errors.add("第" + (i+1) + "行：" + rootMessage(ex)); }
            }
        } catch (Exception ex) {
            if (total == 0) throw new IllegalArgumentException("Excel读取失败：" + rootMessage(ex));
        }
        return new ImportResult(total, success, errors, employeeNos);
    }

    public byte[] exportSalaries(String month) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT e.employee_no,e.name,s.department_name_snapshot,s.position_name_snapshot,s.department_base_salary,
            s.position_salary,s.reward,s.subsidy,s.overtime_pay,s.other_income,s.deduction,s.other_deduction,
            s.payable_salary,s.actual_salary FROM salary_record s JOIN employee e ON e.id=s.employee_id
            WHERE s.salary_month=? ORDER BY e.employee_no
            """, month);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(month + "工资表");
            String[] headers = {"工号","姓名","部门","职位","部门基础工资","岗位工资","奖励","补贴","加班工资","其他收入","扣款","其他扣款","应发工资","实发工资"};
            Row head = sheet.createRow(0);
            for (int i=0;i<headers.length;i++) head.createCell(i).setCellValue(headers[i]);
            for (int r=0;r<rows.size();r++) {
                Row row = sheet.createRow(r+1); Map<String,Object> data=rows.get(r);
                String[] keys={"employee_no","name","department_name_snapshot","position_name_snapshot","department_base_salary","position_salary","reward","subsidy","overtime_pay","other_income","deduction","other_deduction","payable_salary","actual_salary"};
                for(int c=0;c<keys.length;c++) {
                    Object v=data.get(keys[c]);
                    if(v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue()); else row.createCell(c).setCellValue(v==null?"":v.toString());
                }
            }
            for(int i=0;i<headers.length;i++) sheet.autoSizeColumn(i);
            wb.write(out); return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("工资表导出失败", ex); }
    }

    private LocalDate parseDate(Cell cell, DataFormatter fmt) {
        boolean numeric = cell != null && (cell.getCellType() == CellType.NUMERIC
                || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC));
        if (numeric && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate();
        return LocalDate.parse(fmt.formatCellValue(cell).trim());
    }
    private String rootMessage(Throwable e) { while(e.getCause()!=null) e=e.getCause(); return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
}
