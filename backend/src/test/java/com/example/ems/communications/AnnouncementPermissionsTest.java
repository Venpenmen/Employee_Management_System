package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import com.example.ems.upgrade.CompatibilityCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnouncementPermissionsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CommunicationAnnouncementService service = new CommunicationAnnouncementService(
        jdbc, new ObjectMapper(), mock(CommunicationAccess.class),
        mock(CommunicationAttachmentService.class), mock(ApplicationEventPublisher.class));
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");

    private void mockCurrentRole(int role, long department) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql=call.getArgument(0);
            if (sql.contains("SELECT e.user_type")) return List.of(Map.of(
                "user_type",role,"department_id",department,"employment_status",1,"account_status",1));
            lastQuery.set(sql);
            return List.of();
        });
    }

    private static SessionUser user(int claimedRole) {
        return new SessionUser(77L,"000077","Test User",claimedRole,9L,"Department");
    }

    @Test
    void mineAlwaysFiltersByCreatorEvenForExecutivesOrFormerManagers() {
        mockCurrentRole(0,9L);
        assertTrue(service.mine(user(2),null,50).isEmpty());
        assertTrue(lastQuery.get().contains("WHERE a.created_by=?"));
        assertFalse(lastQuery.get().contains("comm_announcement_handover"));
    }

    @Test
    void managerViewIncludesExplicitHandoversButNotEverything() {
        mockCurrentRole(1,9L);
        assertTrue(service.manage(user(1),null,null,50).isEmpty());
        assertTrue(lastQuery.get().contains("a.created_by=?"));
        assertTrue(lastQuery.get().contains("h.to_manager_id=?"));
        assertTrue(lastQuery.get().contains("h.department_id=?"));
        assertTrue(lastQuery.get().contains("h.revoked_at IS NULL"));
    }

    @Test
    void demotionInvalidatesManagementEvenIfSessionStillClaimsManager() {
        mockCurrentRole(0,9L);
        assertThrows(ResponseStatusException.class, () -> service.manage(user(1),null,null,50));
    }

    @Test
    void executiveCanSeeGlobalManagement() {
        mockCurrentRole(2,9L);
        assertTrue(service.manage(user(2),null,null,50).isEmpty());
        assertFalse(lastQuery.get().contains("h.to_manager_id"));
    }

    @Test
    void ordinaryStaffCannotAuthorizeHandover() {
        mockCurrentRole(0,9L);
        assertThrows(ResponseStatusException.class,() -> service.handoverCandidates(user(0),3L));
    }

    @Test
    void v5IsRegisteredWithoutRemovingTheOldV4Profile() {
        assertEquals(4, CompatibilityCatalog.profile("canonical-122").schemaVersion());
        assertEquals(5, CompatibilityCatalog.profile("canonical-122-handover").schemaVersion());
        assertTrue(CompatibilityCatalog.profile("canonical-122-handover").tables()
            .containsKey("comm_announcement_handover"));
    }
}
