package com.example.ems.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local, token-authenticated lifecycle API used only by the portable launcher. */
@RestController
@Profile("portable")
@RequestMapping("/internal/portable")
public class PortableControlController {
    private final ConfigurableApplicationContext context;
    private final String token;
    private volatile boolean ready;
    private final AtomicBoolean closing = new AtomicBoolean();

    public PortableControlController(ConfigurableApplicationContext context,
                                     @Value("${ems.instance-token}") String token) {
        if (token.length() < 32) throw new IllegalArgumentException("Portable instance token is missing or too short");
        this.context = context;
        this.token = token;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() { ready = true; }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        if (!authorized(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.status(ready && !closing.get() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("pid", ProcessHandle.current().pid(), "version", "1.2.0"));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(HttpServletRequest request) {
        if (!authorized(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (closing.compareAndSet(false, true)) {
            Thread shutdown = new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                context.close();
            }, "ems-portable-shutdown");
            shutdown.setDaemon(false);
            shutdown.start();
        }
        return ResponseEntity.accepted().build();
    }

    private boolean authorized(HttpServletRequest request) {
        String supplied = request.getHeader("X-EMS-Token");
        return "127.0.0.1".equals(request.getRemoteAddr()) && supplied != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
