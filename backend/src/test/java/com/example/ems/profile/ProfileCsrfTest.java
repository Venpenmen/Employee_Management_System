package com.example.ems.profile;

import com.example.ems.common.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

class ProfileCsrfTest {
    private final ProfileCsrf csrf = new ProfileCsrf();

    @Test
    void tokenIsStablePerSessionAndVerified() {
        MockHttpSession session = new MockHttpSession();
        String token = csrf.token(session);
        assertEquals(token, csrf.token(session));
        assertTrue(token.length() >= 40);
        assertDoesNotThrow(() -> csrf.verify(session, token));
    }

    @Test
    void missingOrWrongTokenIsForbidden() {
        MockHttpSession session = new MockHttpSession();
        String token = csrf.token(session);
        assertThrows(ForbiddenException.class, () -> csrf.verify(session, null));
        assertThrows(ForbiddenException.class, () -> csrf.verify(session, token + "x"));
        assertThrows(ForbiddenException.class, () -> csrf.verify(new MockHttpSession(), token));
    }
}
