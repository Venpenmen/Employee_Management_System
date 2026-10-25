package com.example.ems.profile;

import com.example.ems.common.ConflictException;
import com.example.ems.common.NotFoundException;
import com.example.ems.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;

import java.util.*;

/**
 * 完整个人档案：基本信息、联系方式、教育经历、工作经历、技能、证书及修改记录。
 * <ul>
 *   <li>所有写操作的归属人只取当前登录用户，不接受请求中的员工 ID。</li>
 *   <li>联系方式使用版本号防止旧页面覆盖较新资料。</li>
 *   <li>资料写入与审计日志在同一事务中，日志失败时资料一并回滚。</li>
 *   <li>审计摘要只记录操作和字段名称，不记录联系方式、地址或个人文字内容。</li>
 * </ul>
 */
@Service
public class ProfileService {
    public static final String AUDIT_TARGET = "EMPLOYEE_PROFILE";
    static final int HISTORY_LIMIT = 100;

    /** 基本档案字段 → 审计中显示的字段名称（按页面顺序）。 */
    private static final Map<String, String> BASIC_LABELS = new LinkedHashMap<>();
    static {
        BASIC_LABELS.put("phone", "手机号");
        BASIC_LABELS.put("email", "邮箱");
        BASIC_LABELS.put("address", "通讯地址");
        BASIC_LABELS.put("emergency_name", "紧急联系人");
        BASIC_LABELS.put("emergency_relation", "联系人关系");
        BASIC_LABELS.put("emergency_phone", "紧急联系电话");
        BASIC_LABELS.put("gender", "性别");
        BASIC_LABELS.put("biography", "个人简介");
    }

    private final JdbcTemplate jdbc;
    private final LogService logs;
    private final ProfileValidator validator;

    @Autowired
    public ProfileService(JdbcTemplate jdbc, LogService logs) {
        this(jdbc, logs, new ProfileValidator());
    }

    ProfileService(JdbcTemplate jdbc, LogService logs, ProfileValidator validator) {
        this.jdbc = jdbc; this.logs = logs; this.validator = validator;
    }

    // ---------------------------------------------------------------- 读取

    /** 档案各分区数据。旧员工没有扩展档案时各字段为 null，页面显示“未填写”。 */
    public Map<String, Object> archive(Long employeeId) {
        Map<String, Object> archive = new LinkedHashMap<>();
        archive.put("basic", basic(employeeId));
        archive.put("education", jdbc.queryForList(
                "SELECT * FROM employee_education WHERE employee_id=? ORDER BY current_entry DESC,start_month DESC,id DESC", employeeId));
        archive.put("experience", jdbc.queryForList(
                "SELECT * FROM employee_experience WHERE employee_id=? ORDER BY current_entry DESC,start_month DESC,id DESC", employeeId));
        archive.put("skills", jdbc.queryForList(
                "SELECT * FROM employee_skill WHERE employee_id=? ORDER BY id", employeeId));
        archive.put("certificates", jdbc.queryForList(
                "SELECT * FROM employee_certificate WHERE employee_id=? ORDER BY obtained_date DESC,id DESC", employeeId));
        archive.put("history", jdbc.queryForList(
                "SELECT create_time,summary FROM operation_log WHERE target_type=? AND target_id=? ORDER BY id DESC LIMIT " + HISTORY_LIMIT,
                AUDIT_TARGET, employeeId));
        return archive;
    }

    /** 当前已保存的基本档案，用于填充编辑表单。 */
    public BasicProfileForm basicForm(Long employeeId) {
        Map<String, Object> b = basic(employeeId);
        BasicProfileForm form = new BasicProfileForm();
        form.setVersion(((Number) b.get("version")).intValue());
        form.setPhone(str(b.get("phone")));
        form.setEmail(str(b.get("email")));
        form.setAddress(str(b.get("address")));
        form.setEmergencyName(str(b.get("emergency_name")));
        form.setEmergencyRelation(str(b.get("emergency_relation")));
        form.setEmergencyPhone(str(b.get("emergency_phone")));
        form.setGender(str(b.get("gender")));
        form.setBiography(str(b.get("biography")));
        return form;
    }

    /** 读取本人某条明细到表单；记录不存在或不属于本人时返回 404，不区分两种情况。 */
    public ProfileEntryForm entryForm(ProfileSection section, Long entryId, Long ownerId) {
        Map<String, Object> r = ownedEntry(section, entryId, ownerId);
        ProfileEntryForm f = new ProfileEntryForm();
        switch (section) {
            case EDUCATION -> {
                f.setSchool(str(r.get("school"))); f.setDegree(str(r.get("degree"))); f.setMajor(str(r.get("major")));
                months(f, r);
            }
            case EXPERIENCE -> {
                f.setCompany(str(r.get("company"))); f.setDepartment(str(r.get("department"))); f.setPosition(str(r.get("position")));
                months(f, r);
                f.setDescription(str(r.get("description")));
            }
            case SKILLS -> {
                f.setName(str(r.get("name"))); f.setProficiency(str(r.get("proficiency"))); f.setNotes(str(r.get("notes")));
            }
            case CERTIFICATES -> {
                f.setName(str(r.get("name"))); f.setIssuer(str(r.get("issuer")));
                f.setCertificateNumber(str(r.get("certificate_number")));
                f.setObtainedDate(dateText(r.get("obtained_date"))); f.setExpiryDate(dateText(r.get("expiry_date")));
            }
        }
        return f;
    }

    // ---------------------------------------------------------------- 基本档案

    /**
     * 保存本人联系方式及补充信息。
     * @return 发生变化的字段名称；为空表示没有任何修改
     * @throws ConflictException 表单版本落后于已保存版本（其他页面已保存）
     */
    @Transactional
    public List<String> saveBasic(Long ownerId, BasicProfileForm form, Errors errors) {
        Map<String, Object> values = validator.basic(form, errors);
        if (errors.hasErrors()) return List.of();

        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE phone=? AND id<>?",
                Integer.class, values.get("phone"), ownerId);
        if (duplicate != null && duplicate > 0) {
            errors.rejectValue("phone", "duplicate", "该手机号已被其他员工使用");
            return List.of();
        }

        jdbc.update("INSERT IGNORE INTO employee_profile(employee_id) VALUES(?)", ownerId);
        Map<String, Object> current = jdbc.queryForMap("""
            SELECT e.phone,p.email,p.address,p.emergency_name,p.emergency_relation,p.emergency_phone,p.gender,p.biography,p.version
            FROM employee e JOIN employee_profile p ON p.employee_id=e.id WHERE e.id=? FOR UPDATE
            """, ownerId);
        int savedVersion = ((Number) current.get("version")).intValue();
        if (savedVersion != form.getVersion())
            throw new ConflictException("资料已在其他页面保存，为避免覆盖较新内容，本次修改未保存。");

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> field : BASIC_LABELS.entrySet())
            if (!Objects.equals(str(current.get(field.getKey())), values.get(field.getKey()))) changed.add(field.getValue());
        if (changed.isEmpty()) return changed;

        try {
            jdbc.update("UPDATE employee SET phone=? WHERE id=?", values.get("phone"), ownerId);
        } catch (DuplicateKeyException ex) {
            // 并发注册或修改占用了同一手机号
            errors.rejectValue("phone", "duplicate", "该手机号已被其他员工使用");
            throw new ProfileRejectedException();
        }
        int updated = jdbc.update("""
            UPDATE employee_profile SET email=?,address=?,emergency_name=?,emergency_relation=?,emergency_phone=?,gender=?,biography=?,
                   version=version+1
            WHERE employee_id=? AND version=?
            """, values.get("email"), values.get("address"), values.get("emergency_name"), values.get("emergency_relation"),
                values.get("emergency_phone"), values.get("gender"), values.get("biography"), ownerId, savedVersion);
        if (updated == 0) throw new ConflictException("资料已在其他页面保存，为避免覆盖较新内容，本次修改未保存。");
        logs.log(ownerId, "UPDATE_PROFILE", AUDIT_TARGET, ownerId, "更新基本档案：" + String.join("、", changed));
        return changed;
    }

    // ---------------------------------------------------------------- 明细

    /** 新增本人明细；校验失败返回 null 并登记错误。 */
    @Transactional
    public Long createEntry(ProfileSection section, Long ownerId, ProfileEntryForm form, Errors errors) {
        Map<String, Object> values = validator.entry(section, form, errors);
        if (errors.hasErrors()) return null;
        List<String> columns = section.columns();
        String sql = "INSERT INTO " + section.table() + "(employee_id," + String.join(",", columns) + ") VALUES(?"
                + ",?".repeat(columns.size()) + ")";
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        for (String c : columns) args.add(values.get(c));
        jdbc.update(sql, args.toArray());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        logs.log(ownerId, "CREATE_PROFILE_ENTRY", AUDIT_TARGET, ownerId, "新增" + section.label() + "（记录 " + id + "）");
        return id;
    }

    /** 修改本人明细；记录不属于本人时 404。校验失败返回 false 并登记错误。 */
    @Transactional
    public boolean updateEntry(ProfileSection section, Long entryId, Long ownerId, ProfileEntryForm form, Errors errors) {
        ownedEntry(section, entryId, ownerId);
        Map<String, Object> values = validator.entry(section, form, errors);
        if (errors.hasErrors()) return false;
        List<String> columns = section.columns();
        StringJoiner set = new StringJoiner(",");
        List<Object> args = new ArrayList<>();
        for (String c : columns) { set.add(c + "=?"); args.add(values.get(c)); }
        args.add(entryId); args.add(ownerId);
        if (jdbc.update("UPDATE " + section.table() + " SET " + set + " WHERE id=? AND employee_id=?", args.toArray()) == 0)
            throw new NotFoundException("档案记录不存在");
        logs.log(ownerId, "UPDATE_PROFILE_ENTRY", AUDIT_TARGET, ownerId, "修改" + section.label() + "（记录 " + entryId + "）");
        return true;
    }

    /** 删除本人明细；记录不属于本人时 404。 */
    @Transactional
    public void deleteEntry(ProfileSection section, Long entryId, Long ownerId) {
        if (jdbc.update("DELETE FROM " + section.table() + " WHERE id=? AND employee_id=?", entryId, ownerId) == 0)
            throw new NotFoundException("档案记录不存在");
        logs.log(ownerId, "DELETE_PROFILE_ENTRY", AUDIT_TARGET, ownerId, "删除" + section.label() + "（记录 " + entryId + "）");
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> basic(Long employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT e.phone,p.email,p.address,p.emergency_name,p.emergency_relation,p.emergency_phone,p.gender,p.biography,
                   COALESCE(p.version,0) version
            FROM employee e LEFT JOIN employee_profile p ON p.employee_id=e.id WHERE e.id=?
            """, employeeId);
        if (rows.isEmpty()) throw new NotFoundException("员工不存在");
        return rows.get(0);
    }

    private Map<String, Object> ownedEntry(ProfileSection section, Long entryId, Long ownerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + section.table() + " WHERE id=? AND employee_id=?", entryId, ownerId);
        if (rows.isEmpty()) throw new NotFoundException("档案记录不存在");
        return rows.get(0);
    }

    private static void months(ProfileEntryForm f, Map<String, Object> r) {
        f.setStartMonth(str(r.get("start_month")));
        f.setEndMonth(str(r.get("end_month")));
        f.setCurrentEntry(r.get("current_entry") != null && ((Number) r.get("current_entry")).intValue() == 1);
    }

    private static String dateText(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date d) return d.toLocalDate().toString();
        return value.toString();
    }

    private static String str(Object value) { return value == null ? null : value.toString(); }

    /** 在事务内发现需要回滚的字段错误（错误已登记到 Errors）。 */
    public static class ProfileRejectedException extends RuntimeException {
        public ProfileRejectedException() { super("档案资料未通过校验"); }
    }
}
