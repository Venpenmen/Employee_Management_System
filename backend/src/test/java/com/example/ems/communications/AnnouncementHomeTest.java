package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnouncementHomeTest {
    @Test
    void homeIsRecipientScopedPublishedCurrentVersionNewestFirstAndReadOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommunicationAttachmentService attachments = mock(CommunicationAttachmentService.class);
        var service = new CommunicationAnnouncementService(jdbc, new ObjectMapper(),
                mock(CommunicationAccess.class), attachments, mock(ApplicationEventPublisher.class));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT e.user_type")) {
                return List.of(Map.of("user_type", 0, "department_id", 8L,
                        "employment_status", 1, "account_status", 1));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", 44L);
            row.put("published_at", new java.sql.Timestamp(1_700_000_000_000L));
            row.put("version_id", 55L);
            row.put("title", "Newest notice");
            row.put("category", "通知");
            row.put("body_json", "{\"version\":1,\"blocks\":[]}");
            row.put("requires_ack", 0);
            row.put("pinned_at", null);
            row.put("read_at", null);
            row.put("confirmed_at", null);
            return List.of(row);
        });
        when(attachments.forAnnouncementVersion(55L)).thenReturn(List.of(Map.of("id", 7L)));

        List<Map<String, Object>> result = service.home(
                new SessionUser(9L, "000009", "Employee", 0, 8L, "Department"), 99);

        assertEquals(1, result.size());
        assertEquals(List.of(Map.of("id", 7L)), result.get(0).get("attachments"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).queryForList(sql.capture(), args.capture());
        String homeQuery = sql.getAllValues().stream()
                .filter(value -> value.contains("FROM comm_announcement a"))
                .findFirst().orElseThrow();
        assertTrue(homeQuery.contains("JOIN comm_announcement_recipient"));
        assertTrue(homeQuery.contains("a.announcement_status=1"));
        assertTrue(homeQuery.contains("ORDER BY a.published_at DESC,a.id DESC"));
        assertTrue(Arrays.asList(args.getAllValues().get(args.getAllValues().size() - 1)).contains(5));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void emptyRecipientFeedStaysEmptyWithoutFallbackToManagementVisibility() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommunicationAttachmentService attachments = mock(CommunicationAttachmentService.class);
        var service = new CommunicationAnnouncementService(jdbc, new ObjectMapper(),
                mock(CommunicationAccess.class), attachments, mock(ApplicationEventPublisher.class));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("SELECT e.user_type")
                        ? List.of(Map.of("user_type", 2, "department_id", 8L,
                        "employment_status", 1, "account_status", 1))
                        : List.of());
        assertTrue(service.home(
                new SessionUser(9L, "000009", "Executive", 2, 8L, "Department"), 5).isEmpty());
        verifyNoInteractions(attachments);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
