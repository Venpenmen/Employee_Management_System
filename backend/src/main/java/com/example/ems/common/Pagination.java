package com.example.ems.common;

import java.util.List;

/**
 * 分页结果。页码越界自动修正：小于 1 视为第 1 页，超过末页视为末页；
 * 每页条数只支持 20 / 50 / 100，其他值回退为 20。
 */
public final class Pagination {
    public static final List<Integer> PAGE_SIZES = List.of(20, 50, 100);
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final int page;
    private final int pageSize;
    private final long total;
    private final int totalPages;

    private Pagination(int page, int pageSize, long total, int totalPages) {
        this.page = page; this.pageSize = pageSize; this.total = total; this.totalPages = totalPages;
    }

    public static int normalizePageSize(Integer requested) {
        return requested != null && PAGE_SIZES.contains(requested) ? requested : DEFAULT_PAGE_SIZE;
    }

    public static int normalizePage(Integer requested) {
        return requested == null || requested < 1 ? 1 : requested;
    }

    /** 根据总数修正页码。没有数据时仍显示第 1 / 1 页。 */
    public static Pagination of(Integer requestedPage, Integer requestedPageSize, long total) {
        int size = normalizePageSize(requestedPageSize);
        int pages = total <= 0 ? 1 : (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        int page = Math.min(normalizePage(requestedPage), pages);
        return new Pagination(page, size, Math.max(total, 0), pages);
    }

    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
    public long getTotal() { return total; }
    public int getTotalPages() { return totalPages; }
    public long getOffset() { return (long) (page - 1) * pageSize; }
}
