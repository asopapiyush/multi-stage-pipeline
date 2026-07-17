package com.pipeline;

import com.pipeline.model.*;
import com.pipeline.repository.JobRepository;
import com.pipeline.repository.TestDataSourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class Phase1IntegrationTest {

    private JobRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JobRepository(TestDataSourceFactory.create());
        repository.initializeSchema();
    }

    @Test
    void testFullJobLifecycle() {
        // 1. Create a job
        String jobId = "phase1-test-job";
        JobStatus jobStatus = new JobStatus();
        jobStatus.setJobId(jobId);
        jobStatus.setState(JobState.RUNNING);
        jobStatus.setCreatedAt(System.currentTimeMillis());

        repository.createJob(jobId, jobStatus);

        // 2. Add an item (URL being processed)
        ItemStatus item1 = new ItemStatus();
        item1.setIndex(0);
        item1.setUrl("https://example.com");
        item1.setStage(ProcessingStage.FETCHING);
        item1.setState(ProcessingState.IN_PROGRESS);
        item1.setStartTime(System.currentTimeMillis());

        repository.updateJobItem(jobId, item1);

        // 3. Mark item as analyzing
        item1.setStage(ProcessingStage.ANALYZING);
        repository.updateJobItem(jobId, item1);

        // 4. Save analysis result
        AnalysisResult result = new AnalysisResult(
            0,
            "https://example.com",
            List.of("/page1", "/page2", "/page3"),
            java.util.Map.of(
                "concurrency", 45L,
                "thread", 38L,
                "synchronization", 23L
            ),
            25.3
        );

        repository.saveResult(jobId, result);

        // 5. Mark item as storing/done
        item1.setStage(ProcessingStage.STORING);
        item1.setState(ProcessingState.SUCCESS);
        item1.setEndTime(System.currentTimeMillis());
        repository.updateJobItem(jobId, item1);

        // 6. Update aggregates
        JobAggregate agg = new JobAggregate();
        agg.setDocumentsProcessed(1);
        agg.setAverageReadability(25.3);
        agg.setTopWords(java.util.Map.of(
            "concurrency", 45L,
            "thread", 38L,
            "synchronization", 23L
        ));
        agg.setLastUpdated(System.currentTimeMillis());

        repository.updateAggregate(jobId, agg);

        // 7. Retrieve and verify
        Optional<JobStatus> retrieved = repository.getJob(jobId);
        assertTrue(retrieved.isPresent());

        JobStatus retrievedJob = retrieved.get();
        assertEquals(jobId, retrievedJob.jobId());
        assertEquals(JobState.RUNNING, retrievedJob.state());

        // Verify items
        assertNotNull(retrievedJob.items());
        assertEquals(1, retrievedJob.items().size());
        assertEquals("https://example.com", retrievedJob.items().get(0).url());
        assertEquals(ProcessingStage.STORING, retrievedJob.items().get(0).stage());
        assertEquals(ProcessingState.SUCCESS, retrievedJob.items().get(0).state());

        // Verify aggregates
        assertNotNull(retrievedJob.aggregates());
        assertEquals(1, retrievedJob.aggregates().documentsProcessed());
        assertEquals(25.3, retrievedJob.aggregates().averageReadability());
        assertEquals(3, retrievedJob.aggregates().topWords().size());
    }

    @Test
    void testMultipleItems() {
        String jobId = "phase1-multi-item-test";
        JobStatus jobStatus = new JobStatus();
        jobStatus.setJobId(jobId);
        jobStatus.setState(JobState.RUNNING);
        jobStatus.setCreatedAt(System.currentTimeMillis());

        repository.createJob(jobId, jobStatus);

        // Add 3 items
        for (int i = 0; i < 3; i++) {
            ItemStatus item = new ItemStatus();
            item.setIndex(i);
            item.setUrl("https://example.com/page" + i);
            item.setStage(ProcessingStage.QUEUED);
            item.setState(ProcessingState.PENDING);
            item.setStartTime(System.currentTimeMillis());

            repository.updateJobItem(jobId, item);
        }

        Optional<JobStatus> retrieved = repository.getJob(jobId);
        assertTrue(retrieved.isPresent());
        assertEquals(3, retrieved.get().items().size());
    }

    @Test
    void testJobNotFound() {
        Optional<JobStatus> retrieved = repository.getJob("non-existent");
        assertTrue(retrieved.isEmpty());
    }

    @Test
    void testListJobs() {
        // Create 2 jobs
        for (int i = 0; i < 2; i++) {
            String jobId = "list-test-job-" + i;
            JobStatus status = new JobStatus();
            status.setJobId(jobId);
            status.setState(JobState.COMPLETED);
            status.setCreatedAt(System.currentTimeMillis());

            repository.createJob(jobId, status);
        }

        List<JobStatus> jobs = repository.listJobs();
        assertTrue(jobs.size() >= 2);
    }

    @Test
    void testEnumSerialization() {
        // Test that all enums serialize/deserialize correctly
        String jobId = "enum-test-job";
        JobStatus jobStatus = new JobStatus();
        jobStatus.setJobId(jobId);
        jobStatus.setState(JobState.RUNNING);
        jobStatus.setCreatedAt(System.currentTimeMillis());

        repository.createJob(jobId, jobStatus);

        ItemStatus item = new ItemStatus();
        item.setIndex(0);
        item.setUrl("https://test.com");
        item.setStage(ProcessingStage.ANALYZING);
        item.setState(ProcessingState.IN_PROGRESS);
        item.setStartTime(System.currentTimeMillis());

        repository.updateJobItem(jobId, item);

        Optional<JobStatus> retrieved = repository.getJob(jobId);
        assertTrue(retrieved.isPresent());

        ItemStatus retrievedItem = retrieved.get().items().get(0);
        assertEquals(ProcessingStage.ANALYZING, retrievedItem.stage());
        assertEquals(ProcessingState.IN_PROGRESS, retrievedItem.state());
    }
}
