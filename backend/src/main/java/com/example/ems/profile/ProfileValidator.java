package com.example.ems.profile;

import com.example.ems.common.ViewFormats;
import org.springframework.validation.Errors;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 个人档案表单校验与规整：去除首尾空白，空字符串存为 NULL，长度按字符计算。
 * 校验失败只登记错误，不抛异常，由控制器保留用户输入回显。
 */
public class ProfileValidator {
    public static final Set<String> PROFICIENCY = Set.of("了解", "熟悉", "熟练", "精通");
    private static final String MOBILE = "^1\\d{10}$";
    private static final String EMAIL = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";
    private static final YearMonth EARLIEST_MONTH = YearMonth.of(1950, 1);

    private final Clock clock;

    public ProfileValidator() { this(Clock.system(ViewFormats.ZONE)); }
    public ProfileValidator(Clock clock) { this.clock = clock; }

    /** 校验基本档案，返回规整后的值（key 为数据库列名，phone 属于 employee 表）。 */
    public Map<String, Object> basic(BasicProfileForm form, Errors errors) {
        Map<String, Object> v = new LinkedHashMap<>();
        String phone = clean(form.getPhone());
        if (phone == null) errors.rejectValue("phone", "required", "手机号不能为空");
        else if (!phone.matches(MOBILE)) errors.rejectValue("phone", "format", "手机号必须是以1开头的11位数字");
        v.put("phone", phone);

        String email = text(form.getEmail(), "email", "邮箱", 100, errors);
        if (email != null && !email.matches(EMAIL)) errors.rejectValue("email", "format", "邮箱格式不正确");
        v.put("email", email);
        v.put("address", text(form.getAddress(), "address", "通讯地址", 255, errors));
        v.put("emergency_name", text(form.getEmergencyName(), "emergencyName", "紧急联系人", 50, errors));
        v.put("emergency_relation", text(form.getEmergencyRelation(), "emergencyRelation", "联系人关系", 30, errors));

        String emergencyPhone = clean(form.getEmergencyPhone());
        if (emergencyPhone != null && !emergencyPhone.matches(MOBILE))
            errors.rejectValue("emergencyPhone", "format", "紧急联系电话必须是以1开头的11位数字");
        v.put("emergency_phone", emergencyPhone);

        String gender = clean(form.getGender());
        if (gender != null && !gender.equals("0") && !gender.equals("1")) errors.rejectValue("gender", "invalid", "性别选项无效");
        v.put("gender", gender);
        v.put("biography", text(form.getBiography(), "biography", "个人简介", 1000, errors));

        if (form.getVersion() == null || form.getVersion() < 0) errors.reject("version", "页面已过期，请重新载入后再编辑");
        return v;
    }

    /** 校验明细记录，返回与 {@link ProfileSection#columns()} 对应的列值。 */
    public Map<String, Object> entry(ProfileSection section, ProfileEntryForm f, Errors errors) {
        Map<String, Object> v = new LinkedHashMap<>();
        switch (section) {
            case EDUCATION -> {
                v.put("school", required(f.getSchool(), "school", "学校", 100, errors));
                v.put("degree", required(f.getDegree(), "degree", "学历", 30, errors));
                v.put("major", text(f.getMajor(), "major", "专业", 100, errors));
                months(f, "入学月份", v, errors);
            }
            case EXPERIENCE -> {
                v.put("company", required(f.getCompany(), "company", "单位", 100, errors));
                v.put("department", text(f.getDepartment(), "department", "部门", 100, errors));
                v.put("position", text(f.getPosition(), "position", "职位", 100, errors));
                months(f, "开始月份", v, errors);
                v.put("description", text(f.getDescription(), "description", "工作描述", 1000, errors));
            }
            case SKILLS -> {
                v.put("name", required(f.getName(), "name", "技能名称", 100, errors));
                String level = clean(f.getProficiency());
                if (level == null) errors.rejectValue("proficiency", "required", "请选择熟练程度");
                else if (!PROFICIENCY.contains(level)) errors.rejectValue("proficiency", "invalid", "熟练程度选项无效");
                v.put("proficiency", level);
                v.put("notes", text(f.getNotes(), "notes", "技能说明", 500, errors));
            }
            case CERTIFICATES -> {
                v.put("name", required(f.getName(), "name", "证书名称", 100, errors));
                v.put("issuer", required(f.getIssuer(), "issuer", "颁发机构", 100, errors));
                v.put("certificate_number", text(f.getCertificateNumber(), "certificateNumber", "证书编号", 100, errors));
                LocalDate obtained = date(f.getObtainedDate(), "obtainedDate", "取得日期", true, errors);
                LocalDate expiry = date(f.getExpiryDate(), "expiryDate", "到期日期", false, errors);
                if (obtained != null && obtained.isAfter(LocalDate.now(clock)))
                    errors.rejectValue("obtainedDate", "future", "取得日期不能晚于今天");
                if (obtained != null && expiry != null && expiry.isBefore(obtained))
                    errors.rejectValue("expiryDate", "range", "到期日期不能早于取得日期");
                v.put("obtained_date", obtained);
                v.put("expiry_date", expiry);
            }
        }
        return v;
    }

    private void months(ProfileEntryForm f, String startLabel, Map<String, Object> v, Errors errors) {
        YearMonth now = YearMonth.now(clock);
        YearMonth start = month(f.getStartMonth(), "startMonth", startLabel, true, errors);
        if (start != null && start.isAfter(now)) errors.rejectValue("startMonth", "future", startLabel + "不能晚于本月");
        YearMonth end = null;
        if (f.isCurrentEntry()) {
            f.setEndMonth(null); // 在读／在职时结束月份清空
        } else {
            end = month(f.getEndMonth(), "endMonth", "结束月份", true, errors);
            if (start != null && end != null && end.isBefore(start))
                errors.rejectValue("endMonth", "range", "结束月份不能早于" + startLabel);
        }
        v.put("start_month", start == null ? null : start.toString());
        v.put("end_month", end == null ? null : end.toString());
        v.put("current_entry", f.isCurrentEntry() ? 1 : 0);
    }

    private YearMonth month(String raw, String field, String label, boolean required, Errors errors) {
        String s = clean(raw);
        if (s == null) {
            if (required) errors.rejectValue(field, "required", "请填写" + label);
            return null;
        }
        if (!s.matches(MONTH)) { errors.rejectValue(field, "format", label + "格式应为YYYY-MM"); return null; }
        YearMonth value = YearMonth.parse(s);
        if (value.isBefore(EARLIEST_MONTH)) { errors.rejectValue(field, "range", label + "不能早于1950年"); return null; }
        return value;
    }

    private LocalDate date(String raw, String field, String label, boolean required, Errors errors) {
        String s = clean(raw);
        if (s == null) {
            if (required) errors.rejectValue(field, "required", "请填写" + label);
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(s);
            if (d.getYear() < 1950 || d.getYear() > 2200) { errors.rejectValue(field, "range", label + "超出有效范围"); return null; }
            return d;
        } catch (DateTimeParseException ex) {
            errors.rejectValue(field, "format", label + "格式应为YYYY-MM-DD");
            return null;
        }
    }

    private String required(String raw, String field, String label, int max, Errors errors) {
        String s = text(raw, field, label, max, errors);
        if (s == null && clean(raw) == null) errors.rejectValue(field, "required", label + "不能为空");
        return s;
    }

    private String text(String raw, String field, String label, int max, Errors errors) {
        String s = clean(raw);
        if (s == null) return null;
        if (s.codePointCount(0, s.length()) > max) {
            errors.rejectValue(field, "length", label + "不能超过" + max + "个字");
            return null;
        }
        return s;
    }

    static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replace("\r\n", "\n").strip();
        return s.isEmpty() ? null : s;
    }
}
