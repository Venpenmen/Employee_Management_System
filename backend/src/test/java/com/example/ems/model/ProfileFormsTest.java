package com.example.ems.model;

import com.example.ems.service.LogService;
import com.example.ems.service.ProfileService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileFormsTest {
    @Test void validatesDateRangesAndCurrentStatus(){
        assertThrows(IllegalArgumentException.class,()->new ProfileForms.Education("学校","本科","","2024-09","2023-06",false).values());
        assertThrows(IllegalArgumentException.class,()->new ProfileForms.Experience("公司","","","2020-01","2025-01",true,"").values());
        assertThrows(IllegalArgumentException.class,()->new ProfileForms.Education("学校","本科","","2020-01","",false).values());
        assertEquals(1,new ProfileForms.Education("学校","本科","","2020-01","",true).values().get("current_entry"));
        assertThrows(IllegalArgumentException.class,()->new ProfileForms.Certificate("证书","机构","","2026-02-30","").values());
        assertThrows(IllegalArgumentException.class,()->new ProfileForms.Certificate("证书","机构","","2026-01-01","2025-12-31").values());
    }
    @Test void beanValidationRejectsInvalidContactsAndLengths(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();
            var bad=new ProfileForms.Basic("123","invalid","x".repeat(256),"","","bad","","",0L);
            var errors=validator.validate(bad);
            assertTrue(errors.size()>=4);
            assertFalse(validator.validate(new ProfileForms.Skill("技能","随便填写","")).isEmpty());
        }
    }
    @Test void managersCannotReadAnotherPersonsArchive(){
        var jdbc=mock(JdbcTemplate.class);
        var logs=mock(LogService.class);
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var service=new ProfileService(jdbc,logs,factory.getValidator());
            var manager=new SessionUser(2L,"260002","经理",1,1L,"A部门");
            assertThrows(ResponseStatusException.class,()->service.view(5L,manager));
            verifyNoInteractions(jdbc,logs);
        }
    }
}
