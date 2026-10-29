package com.example.ems.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortableControlControllerTest {
    private static final String TOKEN = "a".repeat(64);

    private MockHttpServletRequest request(String address, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        if (token != null) request.addHeader("X-EMS-Token", token);
        return request;
    }

    @Test
    void requiresLocalAddressAndCorrectTokenForStatusAndShutdown() {
        var context = mock(ConfigurableApplicationContext.class);
        var controller = new PortableControlController(context, TOKEN);
        for (var request : new MockHttpServletRequest[]{request("127.0.0.1", null),
                request("127.0.0.1", "wrong"), request("192.168.1.2", TOKEN)}) {
            assertEquals(403, controller.status(request).getStatusCode().value());
            assertEquals(403, controller.stop(request).getStatusCode().value());
        }
        verifyNoInteractions(context);
    }

    @Test
    void readinessWaitsForApplicationReadyAndStopClosesContextOnce() {
        var context = mock(ConfigurableApplicationContext.class);
        var controller = new PortableControlController(context, TOKEN);
        var request = request("127.0.0.1", TOKEN);
        assertEquals(503, controller.status(request).getStatusCode().value());
        controller.applicationReady();
        assertEquals(200, controller.status(request).getStatusCode().value());
        assertEquals(202, controller.stop(request).getStatusCode().value());
        assertEquals(202, controller.stop(request).getStatusCode().value());
        assertEquals(503, controller.status(request).getStatusCode().value());
        verify(context, timeout(3000).times(1)).close();
    }

    @Test
    void refusesMissingInstanceIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new PortableControlController(mock(ConfigurableApplicationContext.class), ""));
    }
}
