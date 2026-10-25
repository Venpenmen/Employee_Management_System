package com.example.ems.profile;

import com.example.ems.common.ConflictException;
import com.example.ems.common.NotFoundException;
import com.example.ems.service.LogService;
import com.example.ems.testsupport.FakeJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProfileServiceTest {
    static class RecordingLog extends LogService {
        final List<String> summaries = new ArrayList<>();
        final List<Long> targets = new ArrayList<>();
        boolean fail;
        RecordingLog() { super(new JdbcTemplate()); }
        @Override public void log(Long operatorId, String type, String targetType, Long targetId, String summary) {
            if (fail) throw new IllegalStateException("audit insert failed");
            summaries.add(summary); targets.add(targetId);
        }
    }

    private FakeJdbc jdbc;
    private RecordingLog logs;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        jdbc = new FakeJdbc();
        logs = new RecordingLog();
        service = new ProfileService(jdbc, logs);
        jdbc.onQueryForMap = (sql, args) -> {
            Map<String, Object> saved = new HashMap<>();
            saved.put("phone", "13800000005");
            saved.put("email", null);
            saved.put("version", 3);
            return saved;
        };
    }

    private static BasicProfileForm form(int version) {
        BasicProfileForm f = new BasicProfileForm();
        f.setVersion(version);
        f.setPhone("13912345678");
        f.setEmail("private@example.com");
        f.setAddress("某市某路1号");
        return f;
    }

    @Test
    void staleFormIsRejectedWithoutOverwriting() {
        BasicProfileForm f = form(2);
        assertThrows(ConflictException.class, () -> service.saveBasic(7L, f, new BeanPropertyBindingResult(f, "form")));
        assertFalse(jdbc.updated("UPDATE employee_profile"));
        assertFalse(jdbc.updated("UPDATE employee SET phone"));
        assertTrue(logs.summaries.isEmpty());
    }

    @Test
    void duplicatePhoneKeepsOriginalPhone() {
        jdbc.onQueryForObject = (sql, args) -> sql.contains("phone=?") ? 1 : 0;
        BasicProfileForm f = form(3);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        service.saveBasic(7L, f, errors);
        assertTrue(errors.hasFieldErrors("phone"));
        assertFalse(jdbc.updated("UPDATE employee SET phone"));
        assertFalse(jdbc.updated("UPDATE employee_profile"));
    }

    @Test
    void savesForOwnerAndAuditsFieldNamesOnly() {
        BasicProfileForm f = form(3);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        List<String> changed = service.saveBasic(7L, f, errors);
        assertFalse(errors.hasErrors());
        assertEquals(List.of("手机号", "邮箱", "通讯地址"), changed);
        FakeJdbc.Call phone = jdbc.call("UPDATE employee SET phone");
        assertEquals(List.of("13912345678", 7L), phone.args());
        FakeJdbc.Call profile = jdbc.call("UPDATE employee_profile");
        assertEquals(7L, profile.args().get(7));
        assertEquals(3, profile.args().get(8));
        String summary = logs.summaries.get(0);
        assertEquals("更新基本档案：手机号、邮箱、通讯地址", summary);
        assertFalse(summary.contains("139") || summary.contains("example.com") || summary.contains("某路"));
        assertEquals(7L, logs.targets.get(0));
    }

    @Test
    void auditFailurePropagatesSoTheTransactionRollsBack() {
        logs.fail = true;
        BasicProfileForm f = form(3);
        assertThrows(IllegalStateException.class, () -> service.saveBasic(7L, f, new BeanPropertyBindingResult(f, "form")));
    }

    @Test
    void unchangedProfileWritesNothing() {
        BasicProfileForm f = new BasicProfileForm();
        f.setVersion(3);
        f.setPhone("13800000005");
        List<String> changed = service.saveBasic(7L, f, new BeanPropertyBindingResult(f, "form"));
        assertTrue(changed.isEmpty());
        assertFalse(jdbc.updated("UPDATE employee_profile"));
        assertTrue(logs.summaries.isEmpty());
    }

    @Test
    void anotherPersonsEntryCannotBeEditedOrDeleted() {
        jdbc.onQueryForList = (sql, args) -> new ArrayList<>(); // WHERE id=? AND employee_id=? 无结果
        ProfileEntryForm f = new ProfileEntryForm();
        f.setName("Java"); f.setProficiency("熟练");
        assertThrows(NotFoundException.class,
                () -> service.updateEntry(ProfileSection.SKILLS, 99L, 7L, f, new BeanPropertyBindingResult(f, "form")));
        assertThrows(NotFoundException.class, () -> service.entryForm(ProfileSection.SKILLS, 99L, 7L));
        jdbc.onUpdate = (sql, args) -> 0;
        assertThrows(NotFoundException.class, () -> service.deleteEntry(ProfileSection.SKILLS, 99L, 7L));
        assertTrue(logs.summaries.isEmpty());
    }

    @Test
    void invalidEntryWritesNothing() {
        ProfileEntryForm f = new ProfileEntryForm();
        f.setCompany("某公司"); f.setStartMonth("2023-05"); f.setEndMonth("2021-01");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        assertNull(service.createEntry(ProfileSection.EXPERIENCE, 7L, f, errors));
        assertTrue(errors.hasErrors());
        assertTrue(jdbc.updates.isEmpty());
    }

    @Test
    void newEntryBelongsToTheLoggedInOwner() {
        jdbc.onQueryForObject = (sql, args) -> 41L;
        ProfileEntryForm f = new ProfileEntryForm();
        f.setName("Java"); f.setProficiency("熟练"); f.setNotes("Spring Boot");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        assertEquals(41L, service.createEntry(ProfileSection.SKILLS, 7L, f, errors));
        FakeJdbc.Call insert = jdbc.call("INSERT INTO employee_skill");
        assertEquals(List.of(7L, "Java", "熟练", "Spring Boot"), insert.args());
        assertEquals("新增技能（记录 41）", logs.summaries.get(0));
    }
}
