package com.pipeline.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.model.ItemStatus;
import com.pipeline.model.JobAggregate;
import com.pipeline.model.StreamMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobProgressHandler {
    private static final Logger log = LoggerFactory.getLogger(JobProgressHandler.class);

    private final Map<String, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void registerSession(String jobId, WebSocketSession session) {
        subscriptions.computeIfAbsent(jobId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.debug("Registered session for job {}", jobId);
    }

    public void unregisterSession(String jobId, WebSocketSession session) {
        Set<WebSocketSession> sessions = subscriptions.getOrDefault(jobId, Collections.emptySet());
        sessions.remove(session);
        log.debug("Unregistered session for job {}", jobId);
    }

    public void broadcastItemUpdate(String jobId, ItemStatus item) throws IOException {
        StreamMessage message = new StreamMessage(
            "item_update",
            jobId,
            item,
            null,
            System.currentTimeMillis()
        );

        broadcast(jobId, message);
    }

    public void broadcastAggregateUpdate(String jobId, JobAggregate aggregate) throws IOException {
        StreamMessage message = new StreamMessage(
            "aggregate_update",
            jobId,
            null,
            aggregate,
            System.currentTimeMillis()
        );

        broadcast(jobId, message);
    }

    public void broadcastQueueDepth(String jobId, int depth) throws IOException {
        StreamMessage message = new StreamMessage("queue_depth", jobId, null, null, System.currentTimeMillis());
        message.setQueueDepth(depth);
        broadcast(jobId, message);
    }

    public void broadcastJobComplete(String jobId, JobAggregate finalAggregate) throws IOException {
        StreamMessage message = new StreamMessage(
            "job_complete",
            jobId,
            null,
            finalAggregate,
            System.currentTimeMillis()
        );

        broadcast(jobId, message);
    }

    private void broadcast(String jobId, StreamMessage message) throws IOException {
        String json = objectMapper.writeValueAsString(message);
        TextMessage textMessage = new TextMessage(json);

        Set<WebSocketSession> sessions = subscriptions.getOrDefault(jobId, Collections.emptySet());

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException e) {
                    log.warn("Failed to send message to session: {}", e.getMessage());
                }
            }
        }
    }
}
