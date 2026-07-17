package com.pipeline.repository;

import com.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JobRepositoryTest {

    private JobRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JobRepository(TestDataSourceFactory.create());
        repository.initializeSchema();
    }

    @Test
    void testCreateJob() {
        String jobId = "test-job-1";
        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        status.setState(JobState.PENDING);
        status.setCreatedAt(System.currentTimeMillis());

        repository.createJob(jobId, status);

        Optional<JobStatus> retrieved = repository.getJob(jobId);
        assertTrue(retrieved.isPresent());
        assertEquals(jobId, retrieved.get().jobId());
        assertEquals(JobState.PENDING, retrieved.get().state());
    }

    @Test
    void testUpdateJobItem() {
        String jobId = "test-job-2";
        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        status.setState(JobState.RUNNING);
        status.setCreatedAt(System.currentTimeMillis());
        repository.createJob(jobId, status);

        ItemStatus item = new ItemStatus();
        item.setIndex(0);
        item.setUrl("https://example.com");
        item.setStage(ProcessingStage.FETCHING);
        item.setState(ProcessingState.IN_PROGRESS);
        item.setStartTime(System.currentTimeMillis());

        repository.updateJobItem(jobId, item);

        Optional<JobStatus> retrieved = repository.getJob(jobId);
        assertTrue(retrieved.isPresent());
        assertNotNull(retrieved.get().items());
        assertEquals(1, retrieved.get().items().size());
    }

    @Test
    void testSaveResult() {
        String jobId = "test-job-3";
        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        status.setState(JobState.RUNNING);
        status.setCreatedAt(System.currentTimeMillis());
        repository.createJob(jobId, status);

        AnalysisResult result = new AnalysisResult(
            0,
            "https://example.com",
            List.of("/page1", "/page2"),
            java.util.Map.of("hello", 5L, "world", 3L),
            25.5
        );

        repository.saveResult(jobId, result);

        Optional<JobStatus> retrieved = repository.getJob(jobId);
        assertTrue(retrieved.isPresent());
    }

    @Test
    void testUpdateAggregate() {
        String jobId = "test-job-4";
        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        status.setState(JobState.RUNNING);
        status.setCreatedAt(System.currentTimeMillis());
        repository.createJob(jobId, status);

        JobAggregate agg = new JobAggregate();
        agg.setDocumentsProcessed(5);
        agg.setAverageReadability(24.5);
        agg.setLastUpdated(System.currentTimeMillis());

        repository.updateAggregate(jobId, agg);

        Optional<JobStatus> retrieved = repository.getJob(jobId);
        assertTrue(retrieved.isPresent());
        assertEquals(5, retrieved.get().aggregates().documentsProcessed());
    }

    @Test
    void testListJobs() {
        String jobId1 = "test-job-5";
        JobStatus status1 = new JobStatus();
        status1.setJobId(jobId1);
        status1.setState(JobState.COMPLETED);
        status1.setCreatedAt(System.currentTimeMillis());
        repository.createJob(jobId1, status1);

        String jobId2 = "test-job-6";
        JobStatus status2 = new JobStatus();
        status2.setJobId(jobId2);
        status2.setState(JobState.COMPLETED);
        status2.setCreatedAt(System.currentTimeMillis());
        repository.createJob(jobId2, status2);

        List<JobStatus> jobs = repository.listJobs();
        assertTrue(jobs.size() >= 2);
    }

    @Test
    void testJobNotFound() {
        Optional<JobStatus> retrieved = repository.getJob("non-existent-job");
        assertTrue(retrieved.isEmpty());
    }
}
