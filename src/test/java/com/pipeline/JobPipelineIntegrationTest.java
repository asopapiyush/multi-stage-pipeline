package com.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test proving the exact HTTP + WebSocket contract the UI ("UI app" folder,
 * app.js runLiveJob/connectLiveWebSocket) depends on: POST /api/auth/login obtains a
 * bearer token, POST /api/jobs (with that token) starts a job, then
 * /api/jobs/{id}/stream?token=... emits item_update events (with the same "index" the UI
 * uses to address activeJobData.items[index]), aggregate_update events, and finally
 * job_complete once every submitted URL has reached a terminal state. This test exercises
 * the real security filter chain and WebSocket handshake interceptor — auth is not
 * bypassed here, unlike JobControllerTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JobPipelineIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String bearerToken;

    @BeforeEach
    void login() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "test-admin-password"));

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
            "/api/auth/login", new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, loginResponse.getStatusCode(),
            "Login failed — ensure AdminUserSeeder ran with admin/test-admin-password (see application-test.properties)");
        bearerToken = objectMapper.readTree(loginResponse.getBody()).get("token").asText();
        assertNotNull(bearerToken);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    @Test
    void jobLifecycleStreamsItemAggregateAndCompleteEvents() throws Exception {
        // 1. POST /api/jobs with the bearer token
        String body = objectMapper.writeValueAsString(Map.of("urls", List.of("https://example.com")));

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
            "/api/jobs", new HttpEntity<>(body, authHeaders()), String.class);

        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        JsonNode createJson = objectMapper.readTree(createResponse.getBody());
        String jobId = createJson.get("jobId").asText();
        assertNotNull(jobId);

        // 2. Open a real WebSocket client to /api/jobs/{jobId}/stream?token=...
        ConcurrentLinkedQueue<JsonNode> receivedEvents = new ConcurrentLinkedQueue<>();
        CountDownLatch jobCompleteLatch = new CountDownLatch(1);

        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                JsonNode event = objectMapper.readTree(message.getPayload());
                receivedEvents.add(event);
                if ("job_complete".equals(event.get("eventType").asText())) {
                    jobCompleteLatch.countDown();
                }
            }
        };

        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        String wsUrl = "ws://localhost:" + port + "/api/jobs/" + jobId + "/stream?token=" + bearerToken;
        WebSocketSession session = wsClient.execute(handler, wsUrl).get(10, TimeUnit.SECONDS);

        try {
            // 3. Wait for job_complete (real HTTP fetch + full pipeline run)
            boolean completed = jobCompleteLatch.await(60, TimeUnit.SECONDS);
            assertTrue(completed, "Expected job_complete event within 60s. Events received: " + receivedEvents);

            // 4. Verify the event sequence matches what the UI's app.js expects
            Set<String> eventTypes = receivedEvents.stream()
                .map(e -> e.get("eventType").asText())
                .collect(java.util.stream.Collectors.toSet());

            assertTrue(eventTypes.contains("item_update"), "Expected at least one item_update event");
            assertTrue(eventTypes.contains("aggregate_update"), "Expected at least one aggregate_update event");
            assertTrue(eventTypes.contains("job_complete"), "Expected a job_complete event");

            // 5. Verify item_update carries the "index" field the UI uses for
            //    activeJobData.items[item.index] lookups, and matches the submitted URL
            JsonNode itemUpdate = receivedEvents.stream()
                .filter(e -> "item_update".equals(e.get("eventType").asText()))
                .findFirst()
                .orElseThrow();
            JsonNode itemStatus = itemUpdate.get("itemStatus");
            assertEquals(0, itemStatus.get("index").asInt());
            assertEquals("https://example.com", itemStatus.get("url").asText());
            assertEquals("DONE", itemStatus.get("stage").asText());
            assertEquals("SUCCESS", itemStatus.get("state").asText());

            // 6. Verify aggregate_update/job_complete carry the aggregates shape the UI reads
            JsonNode jobComplete = receivedEvents.stream()
                .filter(e -> "job_complete".equals(e.get("eventType").asText()))
                .findFirst()
                .orElseThrow();
            JsonNode aggregates = jobComplete.get("aggregates");
            assertEquals(1, aggregates.get("documentsProcessed").asInt());
            assertTrue(aggregates.has("averageReadability"));
            assertTrue(aggregates.has("topWords"));

            // 7. Cross-check against GET /api/jobs/{id} (what the UI's history/poll path reads)
            ResponseEntity<String> getResponse = restTemplate.exchange(
                "/api/jobs/" + jobId, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
            assertEquals(HttpStatus.OK, getResponse.getStatusCode());
            JsonNode jobStatus = objectMapper.readTree(getResponse.getBody());
            assertEquals("COMPLETED", jobStatus.get("state").asText());
            assertEquals(1, jobStatus.get("aggregates").get("documentsProcessed").asInt());

        } finally {
            session.close();
        }
    }

    @Test
    void unauthenticatedRequestsAreRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/jobs", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
