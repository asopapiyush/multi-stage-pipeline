package com.pipeline.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Autowired
    private JobProgressHandler jobProgressHandler;

    @Autowired
    private JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Value("${app.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new StreamHandler(jobProgressHandler), "/api/jobs/{jobId}/stream")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(allowedOrigins.split(","));
    }

    public static class StreamHandler extends TextWebSocketHandler {

        private final JobProgressHandler progressHandler;

        public StreamHandler(JobProgressHandler progressHandler) {
            this.progressHandler = progressHandler;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            String jobId = extractJobId(session);
            if (jobId != null) {
                progressHandler.registerSession(jobId, session);
                log.info("WebSocket connection established for job {}", jobId);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            String jobId = extractJobId(session);
            if (jobId != null) {
                progressHandler.unregisterSession(jobId, session);
                log.info("WebSocket connection closed for job {}", jobId);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            log.error("WebSocket transport error: {}", exception.getMessage());
            String jobId = extractJobId(session);
            if (jobId != null) {
                progressHandler.unregisterSession(jobId, session);
            }
        }

        private String extractJobId(WebSocketSession session) {
            String uri = session.getUri().getPath();
            // Extract jobId from /api/jobs/{jobId}/stream
            String[] parts = uri.split("/");
            if (parts.length >= 4) {
                return parts[3];  // jobId is at index 3
            }
            return null;
        }
    }
}
