package com.example.ems.portable;

import com.example.ems.EmsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PortableLifecycleControllerTest {
    private final PortableLifecycleController controller =
            new PortableLifecycleController(mock(ConfigurableApplicationContext.class), "instance-token-123");

    @Test
    void statusRequiresTheInstanceToken() {
        assertEquals(403, controller.status(null).getStatusCode().value());
        assertEquals(403, controller.status("wrong").getStatusCode().value());
        assertEquals(403, controller.stop(null).getStatusCode().value());
        assertEquals(403, controller.stop("instance-token-12").getStatusCode().value());
    }

    @Test
    void statusReportsVersionAndProcessId() {
        var response = controller.status("instance-token-123");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(EmsApplication.VERSION, response.getBody().get("version"));
        assertEquals(ProcessHandle.current().pid(), response.getBody().get("pid"));
    }

    @Test
    void emptyConfiguredTokenRejectsEverything() {
        PortableLifecycleController open = new PortableLifecycleController(mock(ConfigurableApplicationContext.class), "");
        assertEquals(403, open.status("").getStatusCode().value());
    }
}
