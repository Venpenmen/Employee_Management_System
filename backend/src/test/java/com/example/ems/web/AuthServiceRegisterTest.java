package com.example.ems.web;

import com.example.ems.service.AuthService;
import com.example.ems.service.LogService;
import com.example.ems.testsupport.FakeJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceRegisterTest {
    private final FakeJdbc jdbc = new FakeJdbc();
    private final AuthService auth = new AuthService(jdbc, new LogService(new JdbcTemplate()) {
        @Override public void log(Long operatorId, String type, String targetType, Long targetId, String summary) {}
    });

    @Test
    void rejectsMalformedRegistrationBeforeTouchingTheDatabase() {
        assertThrows(IllegalArgumentException.class, () -> auth.register("26001", "赵一", "120015", "13800000000", "Passw0rd"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("260010", "赵一", "12001", "13800000000", "Passw0rd"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("260010", "赵一", "12001Y", "13800000000", "Passw0rd"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("260010", "赵一", "120015", "1380000000", "Passw0rd"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("260010", "赵一", "120015", "23800000000", "Passw0rd"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("260010", "赵一", "120015", "13800000000", "short1"));
        assertTrue(jdbc.updates.isEmpty());
    }

    @Test
    void trimsIdentityFieldsButKeepsPasswordSpaces() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 10L); row.put("name", "赵一"); row.put("identity_number", "11010119960512001X"); row.put("employment_status", 0);
        jdbc.onQueryForList = (sql, args) -> new ArrayList<>(List.of(row));
        auth.register(" 260010 ", " 赵一 ", "12001x", " 13800000010 ", " Passw0rd ");
        FakeJdbc.Call activate = jdbc.call("UPDATE employee SET phone");
        assertEquals("13800000010", activate.args().get(0));
        FakeJdbc.Call account = jdbc.call("INSERT INTO user_account");
        String hash = (String) account.args().get(1);
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(" Passw0rd ", hash));
    }

    @Test
    void wrongIdentitySuffixIsRejected() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 10L); row.put("name", "赵一"); row.put("identity_number", "110101199605120015"); row.put("employment_status", 0);
        jdbc.onQueryForList = (sql, args) -> new ArrayList<>(List.of(row));
        assertThrows(IllegalArgumentException.class, () -> auth.register("260010", "赵一", "120016", "13800000010", "Passw0rd"));
        assertTrue(jdbc.updates.isEmpty());
    }
}
