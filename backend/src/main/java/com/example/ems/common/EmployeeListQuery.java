package com.example.ems.common;

import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

/**
 * 员工列表的筛选与分页条件。用于列表查询，也用于调动、调整职位、离职和查看详情后“返回原查询”。
 * 返回参数来自表单隐藏域，属于不可信输入，统一经过 {@link #fromReturnParams} 校验后再使用。
 */
public final class EmployeeListQuery {
    private static final int KEYWORD_MAX = 50;

    private final String keyword;
    private final Long departmentId;
    private final Integer status;
    private final int page;
    private final int pageSize;

    public EmployeeListQuery(String keyword, Long departmentId, Integer status, Integer page, Integer pageSize) {
        this.keyword = cleanKeyword(keyword);
        this.departmentId = departmentId != null && departmentId > 0 ? departmentId : null;
        this.status = status != null && status >= 0 && status <= 3 ? status : null;
        this.page = Pagination.normalizePage(page);
        this.pageSize = Pagination.normalizePageSize(pageSize);
    }

    public static EmployeeListQuery defaults() { return new EmployeeListQuery(null, null, null, 1, Pagination.DEFAULT_PAGE_SIZE); }

    /** 从隐藏域字符串解析；非法值被丢弃而不是报错，保证业务操作完成后总能回到一个有效列表页。 */
    public static EmployeeListQuery fromReturnParams(String keyword, String departmentId, String status, String page, String pageSize) {
        return new EmployeeListQuery(keyword, parseLong(departmentId), parseInt(status), parseInt(page), parseInt(pageSize));
    }

    /** 以修正后的页码生成新的条件。 */
    public EmployeeListQuery withPage(int correctedPage) {
        return new EmployeeListQuery(keyword, departmentId, status, correctedPage, pageSize);
    }

    /** 不允许跨部门筛选的角色，丢弃伪造的部门条件。 */
    public EmployeeListQuery withoutDepartment() {
        return new EmployeeListQuery(keyword, null, status, page, pageSize);
    }

    /** 返回员工列表的相对地址（参数已编码，只包含校验后的值）。 */
    public String url() {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/employees");
        if (keyword != null) b.queryParam("keyword", keyword);
        if (departmentId != null) b.queryParam("departmentId", departmentId);
        if (status != null) b.queryParam("status", status);
        if (page > 1) b.queryParam("page", page);
        if (pageSize != Pagination.DEFAULT_PAGE_SIZE) b.queryParam("pageSize", pageSize);
        // 先 build 再整体编码：花括号等字符也被转义，不会被当作 URI 模板变量
        return b.build().encode(StandardCharsets.UTF_8).toUriString();
    }

    public String getKeyword() { return keyword; }
    public Long getDepartmentId() { return departmentId; }
    public Integer getStatus() { return status; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }

    private static String cleanKeyword(String value) {
        if (value == null) return null;
        String s = value.strip();
        if (s.isEmpty()) return null;
        return s.length() > KEYWORD_MAX ? s.substring(0, KEYWORD_MAX) : s;
    }
    private static Long parseLong(String v) {
        if (v == null || !v.strip().matches("\\d{1,18}")) return null;
        return Long.valueOf(v.strip());
    }
    private static Integer parseInt(String v) {
        if (v == null || !v.strip().matches("\\d{1,9}")) return null;
        return Integer.valueOf(v.strip());
    }
}
