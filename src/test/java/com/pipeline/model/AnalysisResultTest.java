package com.pipeline.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisResultTest {

    @Test
    void testAnalysisResultCreation() {
        List<String> links = List.of("/page1", "/page2");
        Map<String, Long> wordFreq = Map.of("hello", 5L, "world", 3L);

        AnalysisResult result = new AnalysisResult(
            0,
            "https://example.com",
            links,
            wordFreq,
            25.5
        );

        assertEquals(0, result.index());
        assertEquals("https://example.com", result.url());
        assertEquals(2, result.links().size());
        assertEquals(2, result.wordFrequencies().size());
        assertEquals(25.5, result.readabilityScore());
    }

    @Test
    void testAnalysisResultEmptyLinks() {
        AnalysisResult result = new AnalysisResult(
            0,
            "https://example.com",
            List.of(),
            Map.of(),
            0.0
        );

        assertTrue(result.links().isEmpty());
        assertTrue(result.wordFrequencies().isEmpty());
    }
}
