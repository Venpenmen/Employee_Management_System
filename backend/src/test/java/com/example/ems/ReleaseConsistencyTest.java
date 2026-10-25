package com.example.ems;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 版本号、增量建表脚本与便携配置的一致性检查（发布脚本依赖这些约定）。 */
class ReleaseConsistencyTest {
    private static String resource(String name) throws Exception {
        try (InputStream in = ReleaseConsistencyTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void pomVersionMatchesApplicationVersion() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher m = Pattern.compile("<artifactId>employee-management-system</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertTrue(m.find());
        assertEquals(EmsApplication.VERSION, m.group(1));
    }

    @Test
    void profileMigrationOnlyAddsFiveTablesIdempotently() throws Exception {
        String sql = resource("db/migration/profile-v1.1.1.sql");
        assertEquals(5, Pattern.compile("CREATE TABLE IF NOT EXISTS").matcher(sql).results().count());
        assertFalse(sql.toUpperCase().matches("(?s).*\\b(DROP|ALTER|TRUNCATE|DELETE|INSERT)\\b.*"));
    }

    @Test
    void portableProfileIsSelfContainedAndLocalOnly() throws Exception {
        String yml = resource("application-portable.yml");
        assertTrue(yml.contains("address: 127.0.0.1"));
        assertTrue(yml.contains("mode: never"));
        assertTrue(yml.contains("${EMS_DB_PASSWORD:}"));
        assertTrue(yml.contains("${EMS_INSTANCE_TOKEN:}"));
        assertFalse(yml.contains("123456"));
    }

    @Test
    void developmentConfigKeepsQuotedPasswordLineForTheReleaseScript() throws Exception {
        String yml = resource("application.yml");
        assertTrue(Pattern.compile("(?m)^\\s+password:\\s*\"([^\"\\r\\n]*)\"\\s*$").matcher(yml).find());
        assertTrue(yml.contains("classpath:db/migration/profile-v1.1.1.sql"));
    }
}
