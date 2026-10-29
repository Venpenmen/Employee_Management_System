package com.example.ems.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AuthValidationTest {
    @Test void rejectsEmptyShortLongOrMalformedIdentitySuffixBeforeDatabase(){
        for(String suffix:new String[]{null,"","1","12345","1234567","X12345","1234 X"}){
            var jdbc=mock(JdbcTemplate.class);var service=new AuthService(jdbc,mock(LogService.class));
            assertThrows(IllegalArgumentException.class,()->service.register("260009","测试",suffix,"13900000001","Fixture123"));
            verifyNoInteractions(jdbc);
        }
    }
    @Test void trimsFieldsNormalizesSuffixAndPreservesPasswordWhitespace(){
        var jdbc=mock(JdbcTemplate.class);var logs=mock(LogService.class);var service=new AuthService(jdbc,logs);
        when(jdbc.queryForList(anyString(),eq("260009"))).thenReturn(List.of(Map.of("id",9L,"name","测试","identity_number","11010119990101234X","employment_status",0)));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("13900000001"))).thenReturn(0);
        service.register(" 260009 "," 测试 ","01234x"," 13900000001 "," Fixture123 ");
        verify(jdbc).update("UPDATE employee SET phone=?,employment_status=1 WHERE id=?","13900000001",9L);
        var hash=org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(eq("INSERT INTO user_account(employee_id,password_hash) VALUES(?,?)"),eq(9L),hash.capture());
        var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        assertTrue(encoder.matches(" Fixture123 ",hash.getValue().toString()));
        assertFalse(encoder.matches("Fixture123",hash.getValue().toString()));
    }
    @Test void validatesEmployeeNumberAndPhone(){
        var jdbc=mock(JdbcTemplate.class);var service=new AuthService(jdbc,mock(LogService.class));
        for(String no:new String[]{"123","abcdef","1234567"})assertThrows(IllegalArgumentException.class,()->service.register(no,"测试","123456","13900000001","Fixture123"));
        for(String phone:new String[]{"12900000000","123","1390000000x"})assertThrows(IllegalArgumentException.class,()->service.register("260009","测试","123456",phone,"Fixture123"));
        verifyNoInteractions(jdbc);
    }
}
