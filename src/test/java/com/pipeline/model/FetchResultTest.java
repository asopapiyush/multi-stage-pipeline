package com.pipeline.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FetchResultTest {

    @Test
    void testFetchResultSuccess() {
        FetchResult result = new FetchResult(
            0,
            "https://example.com",
            "<html>content</html>",
            FetchStatus.SUCCESS,
            null,
            100L
        );

        assertEquals(0, result.index());
        assertEquals("https://example.com", result.url());
        assertEquals("<html>content</html>", result.content());
        assertEquals(FetchStatus.SUCCESS, result.status());
        assertNull(result.error());
        assertEquals(100L, result.fetchTimeMs());
    }

    @Test
    void testFetchResultError() {
        FetchResult result = new FetchResult(
            1,
            "https://example.com",
            null,
            FetchStatus.FETCH_TIMEOUT,
            "Socket timeout after 5s",
            5000L
        );

        assertEquals(1, result.index());
        assertEquals(FetchStatus.FETCH_TIMEOUT, result.status());
        assertEquals("Socket timeout after 5s", result.error());
        assertNull(result.content());
    }

    @Test
    void testFetchResultInvalidUrl() {
        FetchResult result = new FetchResult(
            2,
            "file:///etc/passwd",
            null,
            FetchStatus.INVALID_URL,
            "Only HTTP(S) schemes allowed",
            0L
        );

        assertEquals(FetchStatus.INVALID_URL, result.status());
    }
}
