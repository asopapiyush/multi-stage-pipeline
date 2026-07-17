package com.pipeline.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemStatusTest {

    @Test
    void testItemStatusCreation() {
        ItemStatus item = new ItemStatus();
        item.setIndex(0);
        item.setUrl("https://example.com");
        item.setStage(ProcessingStage.QUEUED);
        item.setState(ProcessingState.PENDING);
        item.setStartTime(System.currentTimeMillis());

        assertEquals(0, item.index());
        assertEquals("https://example.com", item.url());
        assertEquals(ProcessingStage.QUEUED, item.stage());
        assertEquals(ProcessingState.PENDING, item.state());
    }

    @Test
    void testItemStatusTransition() {
        ItemStatus item = new ItemStatus();
        item.setStage(ProcessingStage.QUEUED);
        item.setState(ProcessingState.PENDING);

        item.setStage(ProcessingStage.FETCHING);
        item.setState(ProcessingState.IN_PROGRESS);

        assertEquals(ProcessingStage.FETCHING, item.stage());
        assertEquals(ProcessingState.IN_PROGRESS, item.state());
    }

    @Test
    void testItemStatusWithError() {
        ItemStatus item = new ItemStatus();
        item.setUrl("https://example.com");
        item.setState(ProcessingState.FAILED);
        item.setError("HTTP timeout");

        assertEquals(ProcessingState.FAILED, item.state());
        assertEquals("HTTP timeout", item.error());
    }
}
