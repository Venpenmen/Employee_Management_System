package com.example.ems.common;

import java.util.List;
import java.util.Map;

/** 列表页数据及其修正后的分页信息。 */
public record PageResult(List<Map<String, Object>> rows, Pagination pagination) {}
