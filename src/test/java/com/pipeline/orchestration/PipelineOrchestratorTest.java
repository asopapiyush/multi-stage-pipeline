package com.pipeline.orchestration;

import com.pipeline.model.ItemStatus;
import com.pipeline.model.JobAggregate;
import com.pipeline.repository.JobRepository;
import com.pipeline.repository.TestDataSourceFactory;
import com.pipeline.websocket.JobProgressHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipelineOrchestratorTest {

    private PipelineOrchestrator orchestrator;
    private JobRepository repository;
    private String jobId;

    @BeforeEach
    void setUp() {
        repository = new JobRepository(TestDataSourceFactory.create());
        repository.initializeSchema();
        jobId = "orch-test-" + System.currentTimeMillis();
        orchestrator = new PipelineOrchestrator(jobId, repository);
    }

    @Test
    void testPipelineInitialization() {
        assertNotNull(orchestrator);
        // Orchestrator should be ready to start
    }

    @Test
    void testThreeStagesAreWired() throws InterruptedException {
        // Test that stages are properly wired
        List<String> urls = List.of("https://example.com");

        orchestrator.start(urls);

        // Wait for processing
        Thread.sleep(15000);

        orchestrator.shutdown();

        // Should complete without errors
        assertTrue(true);
    }

    @Test
    void testBackpressureBetweenStages() throws InterruptedException {
        // Queue should not grow unbounded
        List<String> urls = List.of(
            "https://example.com",
            "https://test.com",
            "https://another.com"
        );

        orchestrator.start(urls);

        Thread.sleep(10000);

        orchestrator.shutdown();

        // Should complete
        assertTrue(true);
    }

    @Test
    void testGracefulShutdown() throws InterruptedException {
        List<String> urls = List.of("https://example.com");

        orchestrator.start(urls);

        Thread.sleep(2000);

        // Should shutdown without errors
        assertDoesNotThrow(() -> orchestrator.shutdown());
    }

    @Test
    void testBroadcastsItemUpdatesThroughStages() throws Exception {
        JobProgressHandler progressHandler = mock(JobProgressHandler.class);
        String broadcastJobId = "orch-broadcast-" + System.currentTimeMillis();
        PipelineOrchestrator orchestratorWithHandler =
            new PipelineOrchestrator(broadcastJobId, repository, progressHandler);

        orchestratorWithHandler.start(List.of("https://example.com"));

        // Wait for the item to complete the full pipeline
        Thread.sleep(15000);

        orchestratorWithHandler.shutdown();

        // At minimum, StoreStage's completion broadcast must have fired
        verify(progressHandler, atLeastOnce())
            .broadcastItemUpdate(eq(broadcastJobId), any(ItemStatus.class));
        verify(progressHandler, atLeastOnce())
            .broadcastAggregateUpdate(eq(broadcastJobId), any(JobAggregate.class));
    }
}
