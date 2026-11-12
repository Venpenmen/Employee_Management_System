package com.example.ems.communications;

import com.example.ems.model.SessionUser;
import com.example.ems.service.SessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableWebSocket
public class CommunicationWebSocketConfig implements WebSocketConfigurer {
    private final CommunicationRealtime realtime;
    private final SessionService sessions;

    public CommunicationWebSocketConfig(CommunicationRealtime realtime, SessionService sessions) {
        this.realtime = realtime;
        this.sessions = sessions;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtime, "/ws/communications")
                .addInterceptors(new SessionHandshakeInterceptor(sessions))
                .setAllowedOriginPatterns("*");
    }

    private static final class SessionHandshakeInterceptor implements HandshakeInterceptor {
        private final SessionService sessions;
        private SessionHandshakeInterceptor(SessionService sessions) { this.sessions = sessions; }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (!sameOrigin(request) || !(request instanceof ServletServerHttpRequest servlet)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            jakarta.servlet.http.HttpSession http = servlet.getServletRequest().getSession(false);
            if (http == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            Object stored;
            try { stored = http.getAttribute("user"); } catch (IllegalStateException closed) { stored = null; }
            if (!(stored instanceof SessionUser user)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            String token = SessionService.token(http);
            if (token == null || sessions.validate(user.id(), token) != SessionService.Validity.VALID) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put("employeeId", user.id());
            attributes.put("sessionToken", token);
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }

        private static boolean sameOrigin(ServerHttpRequest request) {
            List<String> hosts = request.getHeaders().get("Host");
            List<String> origins = request.getHeaders().get("Origin");
            if (hosts == null || hosts.size() != 1 || origins == null || origins.size() != 1) return false;
            try {
                URI origin = URI.create(origins.get(0));
                if (!Set.of("http", "https").contains(String.valueOf(origin.getScheme()).toLowerCase(Locale.ROOT))
                        || origin.getHost() == null) return false;
                int port = origin.getPort() < 0 ? (origin.getScheme().equalsIgnoreCase("https") ? 443 : 80) : origin.getPort();
                String supplied = origin.getHost().toLowerCase(Locale.ROOT) + ":" + port;
                return supplied.equalsIgnoreCase(hosts.get(0).trim());
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }
}
