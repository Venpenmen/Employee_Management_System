package com.example.ems.communications;

import com.example.ems.upgrade.Schema;
import com.example.ems.upgrade.Snapshot;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public final class CommunicationsMigrationMain {
    private static int checks;
    public static void main(String[] args) throws Exception {
        String url = required("EMS_IT_URL"), password = required("EMS_IT_PASSWORD");
        Schema.safeUrl(url);
        Flyway.configure().dataSource(url, "root", password).locations("classpath:db/upgrade")
                .target("3").cleanDisabled(true).load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "root", password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String hash = new BCryptPasswordEncoder().encode("SyntheticPassword123");
        jdbc.update("""
            INSERT INTO department(id,department_name,manager_id,base_salary,status,description)
            VALUES(10,'Legacy Department',1,1000,1,'legacy')
            """);
        jdbc.update("""
            INSERT INTO employee(id,employee_no,name,identity_number,birth_date,phone,department_id,user_type,hire_date,employment_status)
            VALUES(1,'260001','Legacy Manager','110101199001011111','1990-01-01','13800000001',10,1,'2020-01-01',1)
            """);
        jdbc.update("INSERT INTO user_account(employee_id,password_hash,account_status) VALUES(1,?,1)", hash);
        Map<String, String> before = new TreeMap<>();
        for (String table : Schema.TABLES.subList(0, 22)) before.put(table, digest(jdbc, table));
        Schema.migrate(url, "root", password);
        check(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
                String.class).equals("4"), "T1 V3 database upgrades exactly to V4");
        for (String table : Schema.TABLES.subList(0, 22)) {
            check(before.get(table).equals(digest(jdbc, table)), "T1 legacy table digest unchanged: " + table);
        }
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CommunicationOrganizationService organizations = new CommunicationOrganizationService(jdbc, event -> {});
        tx.executeWithoutResult(status -> organizations.bootstrapAll());
        tx.executeWithoutResult(status -> organizations.bootstrapAll());
        check(jdbc.queryForObject("SELECT COUNT(*) FROM comm_group WHERE department_id=10", Integer.class) == 1,
                "T1 department group bootstrap is idempotent");
        check(jdbc.queryForObject("""
                SELECT COUNT(*) FROM comm_membership_period p JOIN comm_group g ON g.conversation_id=p.conversation_id
                 WHERE g.department_id=10 AND p.employee_id=1 AND p.open_marker=1 AND p.member_role=1
                """, Integer.class) == 1, "T1 historical manager receives one department-group admin period");
        Map<String, Object> report = Map.of("passed", true, "exitCode", 0, "checks", checks,
                "scope", "V3 to V4 migration, legacy table digests and idempotent department bootstrap");
        Snapshot.JSON.writeValue(Path.of(args[0]).toFile(), report);
        System.out.println("PASS migration acceptance checks=" + checks);
    }

    private static String digest(JdbcTemplate jdbc, String table) throws Exception {
        String primary = table.equals("employee_profile") ? "employee_id" : "id";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + Snapshot.q(table) + " ORDER BY " + Snapshot.q(primary));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map<String, Object> row : rows) {
            new TreeMap<>(row).forEach((key, value) -> {
                digest.update(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
