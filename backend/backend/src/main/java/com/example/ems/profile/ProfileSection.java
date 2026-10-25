package com.example.ems.profile;

import com.example.ems.common.NotFoundException;

import java.util.Arrays;
import java.util.List;

/** 个人档案中可由本人增删改的明细分区。路径片段与模板中的 kind 保持一致。 */
public enum ProfileSection {
    EDUCATION("education", "教育经历", "employee_education",
            List.of("school", "degree", "major", "start_month", "end_month", "current_entry")),
    EXPERIENCE("experience", "工作经历", "employee_experience",
            List.of("company", "department", "position", "start_month", "end_month", "current_entry", "description")),
    SKILLS("skills", "技能", "employee_skill",
            List.of("name", "proficiency", "notes")),
    CERTIFICATES("certificates", "证书", "employee_certificate",
            List.of("name", "issuer", "certificate_number", "obtained_date", "expiry_date"));

    private final String kind;
    private final String label;
    private final String table;
    private final List<String> columns;

    ProfileSection(String kind, String label, String table, List<String> columns) {
        this.kind = kind; this.label = label; this.table = table; this.columns = columns;
    }

    public String kind() { return kind; }
    public String label() { return label; }
    /** 表名和列名均为本枚举中的常量，不来自用户输入。 */
    public String table() { return table; }
    public List<String> columns() { return columns; }

    public static ProfileSection fromKind(String kind) {
        return Arrays.stream(values()).filter(s -> s.kind.equals(kind)).findFirst()
                .orElseThrow(() -> new NotFoundException("档案分区不存在"));
    }
}
