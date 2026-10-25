package com.example.ems.common;

/** LIKE 条件转义：用户输入中的 %、_ 与 \ 按普通字符匹配。 */
public final class SqlLike {
    private SqlLike() {}
    public static String contains(String value) {
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
