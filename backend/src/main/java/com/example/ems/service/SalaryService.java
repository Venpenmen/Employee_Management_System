package com.example.ems.service;

import com.example.ems.model.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class SalaryService {
    private final JdbcTemplate jdbc;
    private final LogService logService;
    public SalaryService(JdbcTemplate jdbc, LogService logService) { this.jdbc = jdbc; this.logService = logService; }

    public List<Map<String,Object>> list(String month, SessionUser user) {
        String sql = """
            SELECT s.*,e.employee_no,e.name FROM salary_record s JOIN employee e ON e.id=s.employee_id
            WHERE (? IS NULL OR s.salary_month=?)
            """ + (user.isExecutive() ? "" : " AND s.employee_id=? AND s.status=3") + " ORDER BY s.salary_month DESC,e.employee_no";
        return user.isExecutive() ? jdbc.queryForList(sql, month, month) : jdbc.queryForList(sql, month, month, user.id());
    }

    @Transactional
    public int settle(String month, Long operatorId) {
        if (month == null || !month.matches("^\\d{4}-(0[1-9]|1[0-2])$")) throw new IllegalArgumentException("工资月份格式应为YYYY-MM");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM salary_record WHERE salary_month=?", Integer.class, month);
        if (exists != null && exists > 0) throw new IllegalArgumentException("该月份已经执行过月结");
        int count = jdbc.update("""
            INSERT INTO salary_record(employee_id,salary_month,department_name_snapshot,position_name_snapshot,
                department_base_salary,position_salary,payable_salary,actual_salary,status,operator_id)
            SELECT e.id,?,d.department_name,p.position_name,d.base_salary,p.position_salary,
                   d.base_salary+p.position_salary,d.base_salary+p.position_salary,1,?
            FROM employee e JOIN department d ON d.id=e.department_id JOIN position_info p ON p.id=e.position_id
            WHERE e.user_type IN(0,1) AND e.employment_status=1
            """, month, operatorId);
        logService.log(operatorId, "SALARY_SETTLEMENT", "SALARY", null, "生成" + month + "工资条，共" + count + "条");
        return count;
    }

    public void adjust(Long id, BigDecimal reward, BigDecimal subsidy, BigDecimal overtimePay,
                       BigDecimal otherIncome, BigDecimal deduction, BigDecimal otherDeduction, Long operatorId) {
        for (BigDecimal value : List.of(reward, subsidy, overtimePay, otherIncome, deduction, otherDeduction))
            if (value.signum() < 0) throw new IllegalArgumentException("工资项目不能为负数");
        jdbc.update("""
            UPDATE salary_record SET reward=?,subsidy=?,overtime_pay=?,other_income=?,deduction=?,other_deduction=?,
            payable_salary=department_base_salary+position_salary+?+?+?+?,
            actual_salary=department_base_salary+position_salary+?+?+?+?-?-? WHERE id=? AND status<3
            """, reward,subsidy,overtimePay,otherIncome,deduction,otherDeduction,
                reward,subsidy,overtimePay,otherIncome,reward,subsidy,overtimePay,otherIncome,deduction,otherDeduction,id);
        logService.log(operatorId, "ADJUST_SALARY", "SALARY", id, "调整工资条项目");
    }

    public void publishMonth(String month, Long operatorId) {
        int count = jdbc.update("UPDATE salary_record SET status=3,published_time=NOW() WHERE salary_month=? AND status<3", month);
        logService.log(operatorId, "PUBLISH_SALARY", "SALARY", null, "发布" + month + "工资条，共" + count + "条");
    }
}
