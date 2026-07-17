package com.pipeline.service;

import com.pipeline.model.JobAggregate;
import com.pipeline.model.JobRequest;
import com.pipeline.model.JobState;
import com.pipeline.model.JobStatus;
import com.pipeline.orchestration.PipelineOrchestrator;
import com.pipeline.repository.JobRepository;
import com.pipeline.websocket.JobProgressHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository repository;
    private final JobProgressHandler progressHandler;
    private final Map<String, PipelineOrchestrator> activeJobs = new ConcurrentHashMap<>();

    public JobService(JobRepository repository, JobProgressHandler progressHandler) {
        this.repository = repository;
        this.progressHandler = progressHandler;
    }

    public String startJob(JobRequest request) {
        // Validate input
        if (request.urls() == null || request.urls().isEmpty()) {
            throw new IllegalArgumentException("URL list cannot be empty");
        }

        if (request.urls().size() > 100) {
            throw new IllegalArgumentException("Maximum 100 URLs per job");
        }

        for (String url : request.urls()) {
            if (url == null || url.isEmpty() || url.length() > 2048) {
                throw new IllegalArgumentException("Invalid URL: " + url);
            }
        }

        // Create job (PENDING)
        String jobId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        repository.createJob(jobId, buildStatus(jobId, JobState.PENDING, createdAt));

        // Start pipeline async; each transition writes its own local JobStatus snapshot
        // rather than mutating one shared object across threads.
        CompletableFuture.runAsync(() -> runJob(jobId, request.urls(), createdAt));

        log.info("Created job {}", jobId);
        return jobId;
    }

    private void runJob(String jobId, List<String> urls, long createdAt) {
        try {
            repository.createJob(jobId, buildStatus(jobId, JobState.RUNNING, createdAt));

            PipelineOrchestrator pipeline = new PipelineOrchestrator(jobId, repository, progressHandler);
            activeJobs.put(jobId, pipeline);

            log.info("Started job {}: {} URLs", jobId, urls.size());

            // Block this async worker thread until every URL reaches a terminal state.
            pipeline.start(urls).join();

            repository.createJob(jobId, buildStatus(jobId, JobState.COMPLETED, createdAt));

            JobAggregate finalAggregate = repository.getJob(jobId)
                .map(JobStatus::aggregates)
                .orElseGet(JobAggregate::new);
            broadcastJobComplete(jobId, finalAggregate);

        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            try {
                repository.createJob(jobId, buildStatus(jobId, JobState.FAILED, createdAt));
            } catch (Exception ex) {
                log.error("Failed to update job status to FAILED", ex);
            }
        } finally {
            activeJobs.remove(jobId);
        }
    }

    private void broadcastJobComplete(String jobId, JobAggregate finalAggregate) {
        try {
            progressHandler.broadcastJobComplete(jobId, finalAggregate);
        } catch (Exception e) {
            log.warn("Failed to broadcast job_complete for {}: {}", jobId, e.getMessage());
        }
    }

    private JobStatus buildStatus(String jobId, JobState state, long createdAt) {
        JobStatus status = new JobStatus();
        status.setJobId(jobId);
        status.setState(state);
        status.setCreatedAt(createdAt);
        status.setUpdatedAt(System.currentTimeMillis());
        return status;
    }

    public Optional<JobStatus> getJob(String jobId) {
        return repository.getJob(jobId);
    }

    public List<JobStatus> listJobs() {
        return repository.listJobs();
    }

    public void cancelJob(String jobId) {
        PipelineOrchestrator pipeline = activeJobs.get(jobId);
        if (pipeline != null) {
            try {
                pipeline.shutdown();
                activeJobs.remove(jobId);
                log.info("Cancelled job {}", jobId);
            } catch (InterruptedException e) {
                log.error("Error cancelling job {}", jobId, e);
                Thread.currentThread().interrupt();
            }
        }
    }
}
