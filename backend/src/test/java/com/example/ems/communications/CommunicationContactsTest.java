package com.example.ems.communications;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunicationContactsTest {
    private CommunicationChatService service(JdbcTemplate jdbc) {
        return new CommunicationChatService(jdbc,
                mock(CommunicationAccess.class),
                mock(CommunicationOrganizationService.class),
                mock(CommunicationAttachmentService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    void includesActiveAccountsWithoutDepartmentAndExcludesDeletedDepartments() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 6L, "name", "Executive")));
        var contacts = service(jdbc).contacts(null, null, 100);
        assertEquals(1, contacts.size());
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        String query = sql.getValue();
        assertTrue(query.contains("LEFT JOIN department"));
        assertTrue(query.contains("(d.id IS NULL OR d.status<>2)"));
        assertTrue(query.contains("e.employment_status=1"));
        assertTrue(query.contains("a.account_status=1"));
        assertFalse(query.contains("e.user_type="));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void preservesKeywordAndDepartmentFilteringAndLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        service(jdbc).contacts("负责人", 8L, 999);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("AND e.department_id=?"));
        assertEquals(List.of("%负责人%", "%负责人%", "%负责人%", 8L, 100),
                java.util.Arrays.asList(args.getValue()));
    }
}
