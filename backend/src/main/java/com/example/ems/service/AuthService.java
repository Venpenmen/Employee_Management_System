package com.example.ems.service;

import com.example.ems.model.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final LogService logService;

    public AuthService(JdbcTemplate jdbc, LogService logService) {
        this.jdbc = jdbc;
        this.logService = logService;
    }

    public SessionUser login(String employeeNo, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT e.id,e.employee_no,e.name,e.user_type,e.department_id,e.employment_status,
                   a.password_hash,a.account_status,a.failed_login_count,a.locked_until,d.department_name
            FROM employee e JOIN user_account a ON a.employee_id=e.id
            LEFT JOIN department d ON d.id=e.department_id WHERE e.employee_no=?
            """, employeeNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("工号或密码错误");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("employment_status")).intValue() != 1 || ((Number) row.get("account_status")).intValue() != 1)
            throw new IllegalArgumentException("账户已停用或员工已离职");
        Object lockValue=row.get("locked_until");
        LocalDateTime locked=lockValue instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)lockValue;
        if (locked != null && locked.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("账户已锁定，请稍后再试");
        Long id = ((Number) row.get("id")).longValue();
        if (!encoder.matches(password, String.valueOf(row.get("password_hash")))) {
            jdbc.update("""
                UPDATE user_account
                SET locked_until=IF(failed_login_count>=4,DATE_ADD(NOW(),INTERVAL 15 MINUTE),locked_until),
                    failed_login_count=IF(failed_login_count>=4,0,failed_login_count+1)
                WHERE employee_id=? AND (locked_until IS NULL OR locked_until<=NOW())
                """, id);
            Integer nowLocked=jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE employee_id=? AND locked_until>NOW()",Integer.class,id);
            throw new IllegalArgumentException(nowLocked!=null&&nowLocked>0 ? "密码错误次数过多，账户已锁定15分钟" : "工号或密码错误");
        }

        Long departmentId = row.get("department_id") == null ? null : ((Number) row.get("department_id")).longValue();
        return new SessionUser(id, employeeNo, String.valueOf(row.get("name")),
                ((Number) row.get("user_type")).intValue(), departmentId,
                row.get("department_name") == null ? "未分配" : String.valueOf(row.get("department_name")));
    }

    @Transactional
    public void register(String employeeNo, String name, String identitySuffix, String phone, String password) {
        employeeNo = employeeNo == null ? "" : employeeNo.trim();
        name = name == null ? "" : name.trim();
        phone = phone == null ? "" : phone.trim();
        if (!employeeNo.matches("[0-9]{6}")) throw new IllegalArgumentException("工号必须为六位数字");
        if (identitySuffix == null || !identitySuffix.matches("[0-9]{5}[0-9Xx]"))
            throw new IllegalArgumentException("身份证后六位必须为六位，前五位为数字，末位为数字或X");
        identitySuffix = identitySuffix.toUpperCase(java.util.Locale.ROOT);
        if (!phone.matches("^1[3-9]\\d{9}$")) throw new IllegalArgumentException("请输入正确的十一位手机号");
        if (password == null || password.length() < 8 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*"))
            throw new IllegalArgumentException("密码至少8位，且必须包含字母和数字");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,name,identity_number,employment_status FROM employee WHERE employee_no=?", employeeNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("工号不存在，请确认已由高层招聘");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("employment_status")).intValue() != 0) throw new IllegalArgumentException("该工号不是待注册状态");
        if (!String.valueOf(row.get("name")).equals(name)) throw new IllegalArgumentException("姓名与招聘记录不一致");
        String identity = String.valueOf(row.get("identity_number"));
        if (!identity.toUpperCase(java.util.Locale.ROOT).endsWith(identitySuffix)) throw new IllegalArgumentException("身份证后六位不匹配");
        Integer phoneCount = jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE phone=?", Integer.class, phone);
        if (phoneCount != null && phoneCount > 0) throw new IllegalArgumentException("手机号已经被使用");
        Long id = ((Number) row.get("id")).longValue();
        jdbc.update("UPDATE employee SET phone=?,employment_status=1 WHERE id=?", phone, id);
        jdbc.update("INSERT INTO user_account(employee_id,password_hash) VALUES(?,?)", id, encoder.encode(password));
        logService.log(id, "REGISTER", "EMPLOYEE", id, "员工完成账户注册");
    }

    @org.springframework.beans.factory.annotation.Autowired private SessionService sessions;
    public void changePassword(Long id,String oldPassword,String newPassword) {
        sessions.changePassword(id,oldPassword,newPassword,logService);
    }

    public String encode(String raw) { return encoder.encode(raw); }
}
