package com.example.ems.profile;

import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

import java.time.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileValidatorTest {
    private final ProfileValidator validator = new ProfileValidator(
            Clock.fixed(LocalDateTime.of(2026, 10, 19, 10, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai")));

    private static BasicProfileForm basic() {
        BasicProfileForm f = new BasicProfileForm();
        f.setVersion(0);
        f.setPhone(" 13800000000 ");
        f.setEmail("zhao@example.com");
        f.setAddress("  ");
        f.setGender("1");
        f.setBiography("你好\r\n世界");
        return f;
    }

    @Test
    void basicProfileIsTrimmedAndBlankBecomesNull() {
        BasicProfileForm f = basic();
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        Map<String, Object> v = validator.basic(f, errors);
        assertFalse(errors.hasErrors());
        assertEquals("13800000000", v.get("phone"));
        assertNull(v.get("address"));
        assertEquals("你好\n世界", v.get("biography"));
    }

    @Test
    void invalidContactDetailsAreReported() {
        BasicProfileForm f = basic();
        f.setPhone("23800000000");
        f.setEmail("not-an-email");
        f.setEmergencyPhone("123");
        f.setGender("2");
        f.setBiography("字".repeat(1001));
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        validator.basic(f, errors);
        for (String field : new String[]{"phone", "email", "emergencyPhone", "gender", "biography"})
            assertTrue(errors.hasFieldErrors(field), field);
    }

    @Test
    void missingVersionIsRejected() {
        BasicProfileForm f = basic();
        f.setVersion(null);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        validator.basic(f, errors);
        assertTrue(errors.hasGlobalErrors());
    }

    @Test
    void currentEducationClearsEndMonth() {
        ProfileEntryForm f = new ProfileEntryForm();
        f.setSchool("某大学"); f.setDegree("本科"); f.setStartMonth("2024-09"); f.setEndMonth("2028-06"); f.setCurrentEntry(true);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        Map<String, Object> v = validator.entry(ProfileSection.EDUCATION, f, errors);
        assertFalse(errors.hasErrors());
        assertNull(v.get("end_month"));
        assertEquals(1, v.get("current_entry"));
    }

    @Test
    void experienceEndBeforeStartIsRejected() {
        ProfileEntryForm f = new ProfileEntryForm();
        f.setCompany("某公司"); f.setStartMonth("2023-05"); f.setEndMonth("2022-01");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        validator.entry(ProfileSection.EXPERIENCE, f, errors);
        assertTrue(errors.hasFieldErrors("endMonth"));
    }

    @Test
    void finishedExperienceRequiresEndMonthAndValidFormat() {
        ProfileEntryForm f = new ProfileEntryForm();
        f.setCompany("某公司"); f.setStartMonth("2023-13");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        validator.entry(ProfileSection.EXPERIENCE, f, errors);
        assertTrue(errors.hasFieldErrors("startMonth"));
        assertTrue(errors.hasFieldErrors("endMonth"));
    }

    @Test
    void skillProficiencyMustBeOneOfTheOptions() {
        ProfileEntryForm f = new ProfileEntryForm();
        f.setName("Java"); f.setProficiency("大师");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        validator.entry(ProfileSection.SKILLS, f, errors);
        assertTrue(errors.hasFieldErrors("proficiency"));
    }

    @Test
    void certificateDatesAreChecked() {
        ProfileEntryForm f = new ProfileEntryForm();
        f.setName("软考"); f.setIssuer("考试中心"); f.setObtainedDate("2027-01-01"); f.setExpiryDate("2020-01-01");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        validator.entry(ProfileSection.CERTIFICATES, f, errors);
        assertTrue(errors.hasFieldErrors("obtainedDate"));
        assertTrue(errors.hasFieldErrors("expiryDate"));

        ProfileEntryForm ok = new ProfileEntryForm();
        ok.setName("软考"); ok.setIssuer("考试中心"); ok.setObtainedDate("2025-05-20");
        BeanPropertyBindingResult okErrors = new BeanPropertyBindingResult(ok, "form");
        Map<String, Object> v = validator.entry(ProfileSection.CERTIFICATES, ok, okErrors);
        assertFalse(okErrors.hasErrors());
        assertEquals(LocalDate.of(2025, 5, 20), v.get("obtained_date"));
        assertNull(v.get("expiry_date"));
    }
}
