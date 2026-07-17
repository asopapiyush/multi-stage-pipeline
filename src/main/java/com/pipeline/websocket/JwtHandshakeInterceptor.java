package com.pipeline.websocket;

import com.pipeline.security.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Validates the JWT on the WebSocket handshake. The token travels as a `?token=` query
 * parameter rather than an Authorization header or Sec-WebSocket-Protocol subprotocol:
 * the native browser WebSocket constructor cannot set custom headers, and subprotocol
 * negotiation is meant for application-level protocol selection, not credentials.
 * Trade-off: query params can appear in server access logs — mitigated by the JWT's
 * short (8h) expiry rather than adding a separate short-lived ticket-exchange endpoint.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    private final JwtService jwtService;

    public JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        HttpServletRequest servlet = servletRequest.getServletRequest();
        String token = servlet.getParameter("token");

        if (token == null || token.isBlank()) {
            log.debug("WebSocket handshake rejected: missing token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            String username = jwtService.validateAndGetUsername(token);
            attributes.put("username", username);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("WebSocket handshake rejected: invalid token ({})", e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
