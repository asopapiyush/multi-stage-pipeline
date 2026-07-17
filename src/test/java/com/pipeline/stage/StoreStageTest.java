package com.pipeline.stage;

import com.pipeline.model.AnalysisResult;
import com.pipeline.model.ItemStatus;
import com.pipeline.model.JobAggregate;
import com.pipeline.repository.JobRepository;
import com.pipeline.repository.TestDataSourceFactory;
import com.pipeline.websocket.JobProgressHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreStageTest {

    private StoreStage storeStage;
    private JobRepository repository;
    private String jobId;

    @BeforeEach
    void setUp() {
        repository = new JobRepository(TestDataSourceFactory.create());
        repository.initializeSchema();
        jobId = "store-test-job-" + System.currentTimeMillis();
        storeStage = new StoreStage(jobId, repository);
    }

    @Test
    void testProcessAndStoreResult() throws InterruptedException {
        AnalysisResult result = new AnalysisResult(
            0,
            "https://example.com",
            List.of("/page1", "/page2"),
            Map.of("hello", 5L, "world", 3L),
            25.5
        );

        storeStage.processResult(result);

        Thread.sleep(500);  // Wait for async storage

        JobAggregate agg = storeStage.getAggregate();
        assertEquals(1, agg.documentsProcessed());
    }

    @Test
    void testAggregateUpdate() throws InterruptedException {
        AnalysisResult result1 = new AnalysisResult(0, "https://url1.com", List.of(), Map.of("word", 1L), 20.0);
        AnalysisResult result2 = new AnalysisResult(1, "https://url2.com", List.of(), Map.of("test", 2L), 30.0);

        storeStage.processResult(result1);
        storeStage.processResult(result2);

        Thread.sleep(1000);

        JobAggregate agg = storeStage.getAggregate();
        assertEquals(2, agg.documentsProcessed());
        double expectedAvg = (20.0 + 30.0) / 2;
        assertEquals(expectedAvg, agg.averageReadability(), 0.01);
    }

    @Test
    void testThreadSafeAggregateUpdates() throws InterruptedException {
        // Simulate concurrent processing (though Stage 3 is single-threaded in real pipeline)
        for (int i = 0; i < 5; i++) {
            AnalysisResult result = new AnalysisResult(
                i,
                "https://url" + i + ".com",
                List.of(),
                Map.of(),
                20.0 + i
            );
            storeStage.processResult(result);
        }

        Thread.sleep(2000);

        JobAggregate agg = storeStage.getAggregate();
        assertEquals(5, agg.documentsProcessed());

        // Average should be (20+21+22+23+24)/5 = 22
        double expectedAvg = (20.0 + 21.0 + 22.0 + 23.0 + 24.0) / 5;
        assertEquals(expectedAvg, agg.averageReadability(), 0.01);
    }

    @Test
    void testTopWordsAggregation() throws InterruptedException {
        AnalysisResult result1 = new AnalysisResult(
            0,
            "https://url1.com",
            List.of(),
            Map.of("concurrency", 45L, "thread", 30L),
            25.0
        );
        AnalysisResult result2 = new AnalysisResult(
            0,
            "https://url2.com",
            List.of(),
            Map.of("concurrency", 20L, "sync", 15L),
            26.0
        );

        storeStage.processResult(result1);
        storeStage.processResult(result2);

        Thread.sleep(1000);

        JobAggregate agg = storeStage.getAggregate();
        assertTrue(agg.topWords().containsKey("concurrency"));
        assertEquals(65L, agg.topWords().get("concurrency"));
    }

    @Test
    void testTopWordsLimitedTo20() throws InterruptedException {
        // Create result with 30 unique words
        Map<String, Long> wordFreq = new java.util.HashMap<>();
        for (int i = 0; i < 30; i++) {
            wordFreq.put("word" + i, (long) (100 - i));
        }

        AnalysisResult result = new AnalysisResult(0, "https://example.com", List.of(), wordFreq, 25.0);
        storeStage.processResult(result);

        Thread.sleep(500);

        JobAggregate agg = storeStage.getAggregate();
        assertTrue(agg.topWords().size() <= 20, "Should keep only top 20 words");
    }

    @Test
    void testReadLockProtection() throws InterruptedException {
        AnalysisResult result = new AnalysisResult(0, "https://example.com", List.of(), Map.of(), 25.0);
        storeStage.processResult(result);

        Thread.sleep(500);

        // Multiple reads should not block each other
        JobAggregate agg1 = storeStage.getAggregate();
        JobAggregate agg2 = storeStage.getAggregate();

        assertEquals(agg1.documentsProcessed(), agg2.documentsProcessed());
    }

    @Test
    void testAggregateIsCopy() throws InterruptedException {
        AnalysisResult result = new AnalysisResult(0, "https://example.com", List.of(), Map.of("test", 1L), 25.0);
        storeStage.processResult(result);

        Thread.sleep(500);

        JobAggregate agg1 = storeStage.getAggregate();
        JobAggregate agg2 = storeStage.getAggregate();

        // Should be different objects (copies)
        assertNotSame(agg1, agg2);
        // But same values
        assertEquals(agg1.documentsProcessed(), agg2.documentsProcessed());
    }

    @Test
    void testBroadcastsItemAndAggregateUpdatesWhenHandlerProvided() throws Exception {
        JobProgressHandler progressHandler = mock(JobProgressHandler.class);
        StoreStage stageWithBroadcast = new StoreStage(jobId, repository, progressHandler);

        AnalysisResult result = new AnalysisResult(
            3,
            "https://example.com/broadcast",
            List.of(),
            Map.of("hello", 2L),
            18.0
        );

        stageWithBroadcast.processResult(result);

        Thread.sleep(500);

        ArgumentCaptor<ItemStatus> itemCaptor = ArgumentCaptor.forClass(ItemStatus.class);
        verify(progressHandler, times(1)).broadcastItemUpdate(eq(jobId), itemCaptor.capture());
        assertEquals(3, itemCaptor.getValue().index());
        assertEquals("https://example.com/broadcast", itemCaptor.getValue().url());

        verify(progressHandler, times(1)).broadcastAggregateUpdate(eq(jobId), any(JobAggregate.class));
    }

    @Test
    void testNoBroadcastWhenHandlerNotProvided() throws InterruptedException {
        // Default two-arg constructor (no progress handler) must not throw
        AnalysisResult result = new AnalysisResult(0, "https://example.com/no-ws", List.of(), Map.of(), 15.0);

        assertDoesNotThrow(() -> storeStage.processResult(result));

        Thread.sleep(500);

        JobAggregate agg = storeStage.getAggregate();
        assertEquals(1, agg.documentsProcessed());
    }
}
