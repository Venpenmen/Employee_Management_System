package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunicationConversationUiTest {
    private CommunicationChatService service(JdbcTemplate jdbc) {
        return new CommunicationChatService(jdbc,
                mock(CommunicationAccess.class),
                mock(CommunicationOrganizationService.class),
                mock(CommunicationAttachmentService.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void directConversationDetailShowsPeerRatherThanGenericTitle() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("id", 5L, "conversation_type", 0),
                        Map.of("id", 12L, "name", "Peer", "employee_no", "000012"));
        Map<String, Object> result = service(jdbc).conversation(5L, 11L);
        assertEquals("Peer", result.get("title"));
        assertEquals("Peer", ((Map<?, ?>) result.get("peer")).get("name"));
    }

    @Test
    void removeFromListPreservesReadReceiptsAndRemovesPin() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(5L))).thenReturn(8L);
        service(jdbc).removeFromList(new SessionUser(11L, "000011", "User", 0, null, null), 5L);
        verify(jdbc).update(argThat(sql -> sql.contains("hidden_at=NOW(6)")
                        && sql.contains("pinned_at=NULL") && !sql.contains("last_read_seq")),
                eq(8L), eq(5L), eq(11L));
        verify(jdbc, never()).update(contains("DELETE"), any(Object[].class));
    }
}
