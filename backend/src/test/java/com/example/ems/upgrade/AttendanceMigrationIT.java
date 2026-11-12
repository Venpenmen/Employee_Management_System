package com.example.ems.upgrade;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttendanceMigrationIT {
    private static final String ROOT = "jdbc:mysql://127.0.0.1:16628/ems_next_dev_v121_att";

    @Test void freshAndV5Upgrade() throws Exception {
        assertEquals("16628", System.getenv("EMS_ATT_ISOLATED_PORT"), "Start an isolated attendance fixture first");
        String fresh = ROOT + "fresh";
        String upgrade = ROOT + "upgrade";
        Flyway.configure().dataSource(upgrade,"root","").locations("classpath:db/upgrade")
                .target("5").load().migrate();
        try (var c = DriverManager.getConnection(upgrade, "root", ""); var s = c.createStatement()) {
            s.executeUpdate("""
                INSERT INTO employee(id,employee_no,name,identity_number,birth_date,hire_date,employment_status)
                VALUES(804,'000804','Fixture','synthetic804','1990-01-01','2020-01-01',1)
                """);
            s.executeUpdate("INSERT INTO attendance_record(employee_id,attendance_date,attendance_status) VALUES(804,'2025-03-03',6)");
            s.executeUpdate("""
                INSERT INTO attendance_record(employee_id,attendance_date,attendance_status,check_in_time,late_minutes)
                VALUES(804,'2025-03-04',2,'2025-03-04 09:07:00',2)
                """);
        }
        for (String url : new String[]{fresh, upgrade}) {
            Schema.migrate(url, "root", "");
            try (var c = DriverManager.getConnection(url, "root", "")) {
                assertEquals("canonical-122-attendance", CompatibilityCatalog.identify(c).id());
            }
        }
        try (var c = DriverManager.getConnection(upgrade,"root",""); var s = c.createStatement();
             var rows = s.executeQuery("SELECT attendance_status,check_in_time,late_minutes FROM attendance_record WHERE employee_id=804 ORDER BY attendance_date")) {
            assertTrue(rows.next());
            assertEquals(6, rows.getInt(1));
            assertNull(rows.getObject(2));
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
            assertEquals(2, rows.getInt(3));
            assertFalse(rows.next());
        }
    }
}
