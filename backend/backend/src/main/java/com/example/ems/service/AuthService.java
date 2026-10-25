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
        employeeNo = trim(employeeNo);
        if (employeeNo == null || password == null) throw new IllegalArgumentException("工号或密码错误");
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
        LocalDateTime locked = (LocalDateTime) row.get("locked_until");
        if (locked != null && locked.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("账户已锁定，请稍后再试");
        Long id = ((Number) row.get("id")).longValue();
        if (!encoder.matches(password, String.valueOf(row.get("password_hash")))) {
            int failed = ((Number) row.get("failed_login_count")).intValue() + 1;
            if (failed >= 5) jdbc.update("UPDATE user_account SET failed_login_count=0,locked_until=DATE_ADD(NOW(),INTERVAL 15 MINUTE) WHERE employee_id=?", id);
            else jdbc.update("UPDATE user_account SET failed_login_count=? WHERE employee_id=?", failed, id);
            throw new IllegalArgumentException(failed >= 5 ? "密码错误次数过多，账户已锁定15分钟" : "工号或密码错误");
        }
        jdbc.update("UPDATE user_account SET failed_login_count=0,locked_until=NULL,last_login_time=NOW() WHERE employee_id=?", id);
        Long departmentId = row.get("department_id") == null ? null : ((Number) row.get("department_id")).longValue();
        return new SessionUser(id, employeeNo, String.valueOf(row.get("name")),
                ((Number) row.get("user_type")).intValue(), departmentId,
                row.get("department_name") == null ? "未分配" : String.valueOf(row.get("department_name")));
    }

    @Transactional
    public void register(String employeeNo, String name, String identitySuffix, String phone, String password) {
        // 工号、姓名、电话去除首尾空格；密码原样保留（空格也是密码的一部分）
        employeeNo = trim(employeeNo); name = trim(name); phone = trim(phone);
        identitySuffix = trim(identitySuffix);
        if (employeeNo == null || !employeeNo.matches("^\\d{6}$")) throw new IllegalArgumentException("工号必须是6位数字");
        if (name == null) throw new IllegalArgumentException("姓名不能为空");
        if (identitySuffix == null || !identitySuffix.matches("^\\d{5}[0-9Xx]$")) throw new IllegalArgumentException("身份证后六位格式不正确（前五位为数字，最后一位为数字或X）");
        if (phone == null || !phone.matches("^1\\d{10}$")) throw new IllegalArgumentException("手机号必须是以1开头的11位数字");
        if (password == null || password.length() < 8 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*"))
            throw new IllegalArgumentException("密码至少8位，且必须包含字母和数字");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,name,identity_number,employment_status FROM employee WHERE employee_no=?", employeeNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("工号不存在，请确认已由高层招聘");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("employment_status")).intValue() != 0) throw new IllegalArgumentException("该工号不是待注册状态");
        if (!String.valueOf(row.get("name")).equals(name)) throw new IllegalArgumentException("姓名与招聘记录不一致");
        String identity = String.valueOf(row.get("identity_number")).toUpperCase();
        if (identity.length() < 6 || !identity.substring(identity.length() - 6).equals(identitySuffix.toUpperCase()))
            throw new IllegalArgumentException("身份证后六位不匹配");
        Integer phoneCount = jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE phone=?", Integer.class, phone);
        if (phoneCount != null && phoneCount > 0) throw new IllegalArgumentException("手机号已经被使用");
        Long id = ((Number) row.get("id")).longValue();
        if (jdbc.update("UPDATE employee SET phone=?,employment_status=1 WHERE id=? AND employment_status=0", phone, id) == 0)
            throw new IllegalArgumentException("该工号不是待注册状态");
        jdbc.update("INSERT INTO user_account(employee_id,password_hash) VALUES(?,?)", id, encoder.encode(password));
        logService.log(id, "REGISTER", "EMPLOYEE", id, "员工完成账户注册");
    }

    public void changePassword(Long employeeId, String oldPassword, String newPassword) {
        if (oldPassword == null || newPassword == null) throw new IllegalArgumentException("请填写原密码和新密码");
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE employee_id=?", String.class, employeeId);
        if (!encoder.matches(oldPassword, hash)) throw new IllegalArgumentException("原密码不正确");
        if (newPassword.length() < 8 || !newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*"))
            throw new IllegalArgumentException("新密码至少8位，且必须包含字母和数字");
        jdbc.update("UPDATE user_account SET password_hash=?,password_update_time=NOW() WHERE employee_id=?", encoder.encode(newPassword), employeeId);
        logService.log(employeeId, "CHANGE_PASSWORD", "ACCOUNT", employeeId, "用户修改密码");
    }

    public String encode(String raw) { return encoder.encode(raw); }

    private static String trim(String value) {
        if (value == null) return null;
        String s = value.strip();
        return s.isEmpty() ? null : s;
    }
}
