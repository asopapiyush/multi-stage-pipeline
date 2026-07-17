package com.pipeline.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobAggregateTest {

    @Test
    void testJobAggregateInitialization() {
        JobAggregate agg = new JobAggregate();

        assertEquals(0, agg.documentsProcessed());
        assertEquals(0, agg.documentsErrored());
        assertEquals(0.0, agg.averageReadability());
        assertNotNull(agg.topWords());
    }

    @Test
    void testJobAggregateUpdate() {
        JobAggregate agg = new JobAggregate();
        agg.setDocumentsProcessed(5);
        agg.setAverageReadability(24.5);
        agg.setLastUpdated(System.currentTimeMillis());

        assertEquals(5, agg.documentsProcessed());
        assertEquals(24.5, agg.averageReadability());
    }

    @Test
    void testJobAggregateTopWords() {
        JobAggregate agg = new JobAggregate();
        Map<String, Long> words = new HashMap<>();
        words.put("concurrency", 45L);
        words.put("thread", 38L);
        agg.setTopWords(words);

        assertEquals(2, agg.topWords().size());
        assertEquals(45L, agg.topWords().get("concurrency"));
    }
}
