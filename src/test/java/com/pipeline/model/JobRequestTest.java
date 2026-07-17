package com.pipeline.model;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobRequestTest {

    @Test
    void testJobRequestCreation() {
        List<String> urls = List.of("https://example.com", "https://test.com");
        JobRequest req = new JobRequest(urls);

        assertNotNull(req);
        assertEquals(2, req.urls().size());
        assertEquals("https://example.com", req.urls().get(0));
    }

    @Test
    void testJobRequestEmptyUrls() {
        JobRequest req = new JobRequest(List.of());
        assertEquals(0, req.urls().size());
    }

    @Test
    void testJobRequestImmutable() {
        List<String> urls = List.of("https://example.com");
        JobRequest req = new JobRequest(urls);

        // Record is immutable
        assertThrows(Exception.class, () -> {
            // Attempting to mutate should fail
            req.urls().clear();
        });
    }
}
