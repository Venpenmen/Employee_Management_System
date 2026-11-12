package com.example.ems.communications;

import com.example.ems.service.SessionService;
import com.example.ems.security.SecuritySessionEndedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class CommunicationRealtime extends TextWebSocketHandler {
    private record Connection(long employeeId, String token, WebSocketSession socket) {}

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final ObjectMapper json;
    private final Map<Long, Set<Connection>> connections = new ConcurrentHashMap<>();

    public CommunicationRealtime(JdbcTemplate jdbc, SessionService sessions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        Long employeeId = (Long) socket.getAttributes().get("employeeId");
        String token = (String) socket.getAttributes().get("sessionToken");
        if (employeeId == null || token == null || !valid(employeeId, token)) {
            socket.close(CloseStatus.NOT_ACCEPTABLE.withReason("登录已失效"));
            return;
        }
        connections.computeIfAbsent(employeeId, ignored -> new CopyOnWriteArraySet<>())
                .add(new Connection(employeeId, token, socket));
        send(socket, "ready", Map.of("status", "connected", "serverTime", Instant.now().toString()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (payload.length() > 512) {
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Long employeeId = (Long) socket.getAttributes().get("employeeId");
        String token = (String) socket.getAttributes().get("sessionToken");
        if (!valid(employeeId, token)) {
            socket.close(CloseStatus.NOT_ACCEPTABLE.withReason("登录已失效"));
            return;
        }
        if (payload.contains("\"ping\"")) send(socket, "pong", Map.of("serverTime", Instant.now().toString()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        remove(socket);
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) {
        remove(socket);
        try { socket.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterCommit(CommunicationChangedEvent event) {
        try {
            if (event == null || event.conversationId() == null) return;
            Long sequence = event.payload() == null || event.payload().get("sequence") == null
                    ? null : ((Number) event.payload().get("sequence")).longValue();
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", event.type());
            frame.put("eventId", UUID.randomUUID().toString());
            frame.put("serverTime", Instant.now().toString());
            frame.put("conversationId", event.conversationId());
            frame.put("payload", event.payload() == null ? Map.of() : event.payload());
            for (Long employeeId : recipients(event.conversationId(), sequence)) {
                Set<Connection> userConnections = connections.get(employeeId);
                if (userConnections == null) continue;
                for (Connection connection : userConnections) {
                    try {
                        if (valid(connection.employeeId(), connection.token())) send(connection.socket(), frame);
                        else connection.socket().close(CloseStatus.NOT_ACCEPTABLE.withReason("登录已失效"));
                    } catch (Exception error) {
                        remove(connection.socket());
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    @EventListener
    public void sessionEnded(SecuritySessionEndedEvent event) {
        if (event == null || event.employeeId() == null || event.tokenHash() == null) return;
        Set<Connection> userConnections = connections.get(event.employeeId());
        if (userConnections == null) return;
        for (Connection connection : userConnections) {
            if (event.tokenHash().equals(SessionService.digest(connection.token()))) {
                try { connection.socket().close(CloseStatus.POLICY_VIOLATION.withReason("登录已失效")); }
                catch (Exception ignored) { remove(connection.socket()); }
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterCommit(CommunicationNotificationEvent event) {
        try {
            if (event == null || event.employeeIds() == null) return;
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", event.type());
            frame.put("eventId", UUID.randomUUID().toString());
            frame.put("serverTime", Instant.now().toString());
            frame.put("payload", event.payload() == null ? Map.of() : event.payload());
            for (Long employeeId : event.employeeIds()) {
                Set<Connection> userConnections = connections.get(employeeId);
                if (userConnections == null) continue;
                for (Connection connection : userConnections) {
                    try {
                        if (valid(connection.employeeId(), connection.token())) send(connection.socket(), frame);
                    } catch (Exception error) {
                        remove(connection.socket());
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        for (Set<Connection> userConnections : connections.values()) {
            for (Connection connection : userConnections) {
                try {
                    if (!valid(connection.employeeId(), connection.token())) {
                        connection.socket().close(CloseStatus.NOT_ACCEPTABLE.withReason("登录已失效"));
                    } else send(connection.socket(), "heartbeat", Map.of("serverTime", Instant.now().toString()));
                } catch (Exception error) {
                    remove(connection.socket());
                }
            }
        }
    }

    private Set<Long> recipients(long conversationId, Long sequence) {
        String sql = sequence == null
                ? "SELECT DISTINCT employee_id FROM comm_membership_period WHERE conversation_id=? AND open_marker=1"
                : """
                  SELECT DISTINCT employee_id FROM comm_membership_period
                   WHERE conversation_id=? AND ? > joined_after_seq
                     AND (left_after_seq IS NULL OR ? <= left_after_seq)
                  """;
        return new HashSet<>(sequence == null
                ? jdbc.queryForList(sql, Long.class, conversationId)
                : jdbc.queryForList(sql, Long.class, conversationId, sequence, sequence));
    }

    private boolean valid(Long employeeId, String token) {
        if (employeeId == null || token == null || sessions.validate(employeeId, token) != SessionService.Validity.VALID) return false;
        Integer active = jdbc.queryForObject("""
            SELECT COUNT(*) FROM employee e JOIN user_account a ON a.employee_id=e.id
             LEFT JOIN account_security s ON s.employee_id=e.id
             WHERE e.id=? AND e.employment_status=1 AND a.account_status=1
               AND (s.employee_id IS NULL OR (s.frozen=0 AND s.temporary_state='NORMAL'))
            """, Integer.class, employeeId);
        return active != null && active == 1;
    }

    private void send(WebSocketSession socket, String type, Object payload) throws Exception {
        if (!socket.isOpen()) return;
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("eventId", UUID.randomUUID().toString());
        frame.put("serverTime", Instant.now().toString());
        frame.put("payload", payload);
        send(socket, frame);
    }

    private void send(WebSocketSession socket, Map<String, Object> frame) throws Exception {
        socket.sendMessage(new TextMessage(json.writeValueAsString(frame)));
    }

    private void remove(WebSocketSession socket) {
        for (Map.Entry<Long, Set<Connection>> entry : connections.entrySet()) {
            entry.getValue().removeIf(connection -> connection.socket() == socket);
            if (entry.getValue().isEmpty()) connections.remove(entry.getKey(), entry.getValue());
        }
    }
}
