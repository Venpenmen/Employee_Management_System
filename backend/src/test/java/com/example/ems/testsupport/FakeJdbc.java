package com.example.ems.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.function.BiFunction;

/** 记录 SQL 的轻量替身，测试不依赖 MySQL。 */
public class FakeJdbc extends JdbcTemplate {
    public record Call(String sql, List<Object> args) {}

    public final List<Call> updates = new ArrayList<>();
    public BiFunction<String, Object[], Integer> onUpdate = (sql, args) -> 1;
    public BiFunction<String, Object[], Object> onQueryForObject = (sql, args) -> 0;
    public BiFunction<String, Object[], Map<String, Object>> onQueryForMap = (sql, args) -> new HashMap<>();
    public BiFunction<String, Object[], List<Map<String, Object>>> onQueryForList = (sql, args) -> new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
        updates.add(new Call(sql, Arrays.asList(args)));
        return onUpdate.apply(sql, args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return (T) onQueryForObject.apply(sql, args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T queryForObject(String sql, Class<T> requiredType) {
        return (T) onQueryForObject.apply(sql, new Object[0]);
    }

    @Override
    public Map<String, Object> queryForMap(String sql, Object... args) {
        return onQueryForMap.apply(sql, args);
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return onQueryForList.apply(sql, args);
    }

    public boolean updated(String fragment) {
        return updates.stream().anyMatch(c -> c.sql().contains(fragment));
    }

    public Call call(String fragment) {
        return updates.stream().filter(c -> c.sql().contains(fragment)).findFirst().orElseThrow();
    }
}
