package com.example.ems.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LogService {
    private final JdbcTemplate jdbc;
    public LogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void log(Long operatorId, String type, String targetType, Long targetId, String summary) {
        jdbc.update("INSERT INTO operation_log(operator_id, operation_type, target_type, target_id, summary) VALUES(?,?,?,?,?)",
                operatorId, type, targetType, targetId, summary);
    }
}
