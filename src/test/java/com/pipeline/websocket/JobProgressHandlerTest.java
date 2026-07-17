package com.pipeline.websocket;

import com.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobProgressHandlerTest {

    private JobProgressHandler handler;
    private ObjectMapper objectMapper;
    private WebSocketSession mockSession;
    private String jobId;

    @BeforeEach
    void setUp() {
        handler = new JobProgressHandler();
        objectMapper = new ObjectMapper();
        mockSession = mock(WebSocketSession.class);
        jobId = "test-job-123";

        // Mock session behavior
        when(mockSession.isOpen()).thenReturn(true);
    }

    @Test
    void testBroadcastItemUpdate() throws IOException {
        // Register a session
        handler.registerSession(jobId, mockSession);

        // Create and broadcast item update
        ItemStatus item = new ItemStatus();
        item.setIndex(0);
        item.setUrl("https://example.com");
        item.setStage(ProcessingStage.FETCHING);
        item.setState(ProcessingState.IN_PROGRESS);

        handler.broadcastItemUpdate(jobId, item);

        // Verify message was sent
        verify(mockSession, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void testBroadcastAggregateUpdate() throws IOException {
        handler.registerSession(jobId, mockSession);

        JobAggregate agg = new JobAggregate();
        agg.setDocumentsProcessed(5);
        agg.setAverageReadability(25.5);

        handler.broadcastAggregateUpdate(jobId, agg);

        verify(mockSession, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void testMultipleSessionsBroadcast() throws IOException {
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);

        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        handler.registerSession(jobId, session1);
        handler.registerSession(jobId, session2);

        ItemStatus item = new ItemStatus();
        item.setIndex(0);
        item.setUrl("https://example.com");
        item.setStage(ProcessingStage.DONE);
        item.setState(ProcessingState.SUCCESS);

        handler.broadcastItemUpdate(jobId, item);

        // Both sessions should receive the message
        verify(session1, times(1)).sendMessage(any(TextMessage.class));
        verify(session2, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void testClosedSessionNotBroadcastedTo() throws IOException {
        WebSocketSession openSession = mock(WebSocketSession.class);
        WebSocketSession closedSession = mock(WebSocketSession.class);

        when(openSession.isOpen()).thenReturn(true);
        when(closedSession.isOpen()).thenReturn(false);

        handler.registerSession(jobId, openSession);
        handler.registerSession(jobId, closedSession);

        ItemStatus item = new ItemStatus();
        item.setStage(ProcessingStage.DONE);

        handler.broadcastItemUpdate(jobId, item);

        // Only open session should receive
        verify(openSession, times(1)).sendMessage(any(TextMessage.class));
        verify(closedSession, times(0)).sendMessage(any(TextMessage.class));
    }

    @Test
    void testUnregisterSession() throws IOException {
        handler.registerSession(jobId, mockSession);
        handler.unregisterSession(jobId, mockSession);

        ItemStatus item = new ItemStatus();
        item.setStage(ProcessingStage.DONE);

        handler.broadcastItemUpdate(jobId, item);

        // No message should be sent after unregister
        verify(mockSession, times(0)).sendMessage(any(TextMessage.class));
    }

    @Test
    void testJobIdIsolation() throws IOException {
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);

        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        handler.registerSession("job-1", session1);
        handler.registerSession("job-2", session2);

        ItemStatus item = new ItemStatus();
        item.setStage(ProcessingStage.DONE);

        handler.broadcastItemUpdate("job-1", item);

        // Only job-1's session should receive
        verify(session1, times(1)).sendMessage(any(TextMessage.class));
        verify(session2, times(0)).sendMessage(any(TextMessage.class));
    }
}
