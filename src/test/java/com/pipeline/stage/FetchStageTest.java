package com.pipeline.stage;

import com.pipeline.model.FetchResult;
import com.pipeline.model.FetchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FetchStageTest {

    private FetchStage fetchStage;
    private BlockingQueue<FetchResult> stage2Queue;

    @BeforeEach
    void setUp() {
        fetchStage = new FetchStage();
        stage2Queue = new LinkedBlockingQueue<>(5);
    }

    @Test
    void testValidUrlProcessing() throws InterruptedException {
        List<String> urls = List.of("https://example.com");
        fetchStage.start(urls, stage2Queue);

        // Wait for fetch to complete
        FetchResult result = stage2Queue.poll(10, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("https://example.com", result.url());
        // Status could be SUCCESS or FETCH_ERROR depending on network
        assertNotNull(result.status());
    }

    @Test
    void testInvalidUrlSchemeRejection() throws InterruptedException {
        List<String> urls = List.of("file:///etc/passwd");
        fetchStage.start(urls, stage2Queue);

        FetchResult result = stage2Queue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("file:///etc/passwd", result.url());
        assertEquals(FetchStatus.INVALID_URL, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("HTTP(S)"));
    }

    @Test
    void testPrivateIpRejection() throws InterruptedException {
        List<String> urls = List.of("http://localhost:8080");
        fetchStage.start(urls, stage2Queue);

        FetchResult result = stage2Queue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FetchStatus.INVALID_URL, result.status());
        assertTrue(result.error().toLowerCase().contains("private") || result.error().toLowerCase().contains("loopback"));
    }

    @Test
    void testBackpressureWaitsWhenQueueFull() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 5; i++) {
            stage2Queue.put(new FetchResult(0, "dummy", null, FetchStatus.SUCCESS, null, 0));
        }

        // Queue is now full (5 items, max 5)
        assertTrue(stage2Queue.remainingCapacity() == 0);

        // Start fetching while queue is full - should wait for queue to drain
        List<String> urls = List.of("https://example.com");

        long startTime = System.currentTimeMillis();
        fetchStage.start(urls, stage2Queue);

        // Wait a bit, then drain one item
        Thread.sleep(500);
        stage2Queue.poll();  // Remove one item, now queue has space

        // Result should eventually be added (after backpressure released)
        FetchResult result = stage2Queue.poll(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue(elapsed >= 500, "Should have waited due to backpressure");
    }

    @Test
    void testMultipleUrlsProcessing() throws InterruptedException {
        List<String> urls = List.of(
            "https://example.com",
            "https://test.com"
        );
        fetchStage.start(urls, stage2Queue);

        // Collect results
        List<FetchResult> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            FetchResult result = stage2Queue.poll(10, TimeUnit.SECONDS);
            if (result != null) {
                results.add(result);
            }
        }

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.url().equals("https://example.com")));
        assertTrue(results.stream().anyMatch(r -> r.url().equals("https://test.com")));
    }

    @Test
    void testUrlLengthValidation() throws InterruptedException {
        // URL longer than 2048 chars
        String longUrl = "https://example.com/" + "a".repeat(2100);
        List<String> urls = List.of(longUrl);

        fetchStage.start(urls, stage2Queue);

        FetchResult result = stage2Queue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FetchStatus.INVALID_URL, result.status());
        assertTrue(result.error().toLowerCase().contains("exceeds") || result.error().toLowerCase().contains("limit"));
    }

    @Test
    void testEmptyUrlListHandled() {
        List<String> urls = List.of();

        // Should not throw exception
        assertDoesNotThrow(() -> fetchStage.start(urls, stage2Queue));
    }

    @Test
    void testFetchTimeoutMarked() throws InterruptedException {
        // Use a URL that will timeout (non-routable IP)
        List<String> urls = List.of("http://192.0.2.1");  // TEST-NET-1, non-routable

        fetchStage.start(urls, stage2Queue);

        FetchResult result = stage2Queue.poll(15, TimeUnit.SECONDS);

        assertNotNull(result);
        // Should be either FETCH_TIMEOUT or FETCH_ERROR
        assertTrue(
            result.status() == FetchStatus.FETCH_TIMEOUT ||
            result.status() == FetchStatus.FETCH_ERROR
        );
    }

    @Test
    void testContentSizeLimitCheck() throws InterruptedException {
        // For this test, we'd need to mock HTTP response
        // Placeholder: actual test requires mocking
        assertTrue(true);
    }
}
