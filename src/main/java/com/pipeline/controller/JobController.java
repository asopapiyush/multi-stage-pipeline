package com.pipeline.controller;

import com.pipeline.model.JobRequest;
import com.pipeline.model.JobStatus;
import com.pipeline.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<?> createJob(@RequestBody JobRequest request, jakarta.servlet.http.HttpServletResponse response) {
        try {
            // Add security headers
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Content-Security-Policy", "default-src 'self'");

            String jobId = jobService.startJob(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("jobId", jobId));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid job request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        try {
            // Validate jobId format (alphanumeric + dash/underscore)
            if (!jobId.matches("^[a-zA-Z0-9-]+$")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid job ID"));
            }

            Optional<JobStatus> job = jobService.getJob(jobId);
            return job.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());

        } catch (Exception e) {
            log.error("Error retrieving job {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }

    @GetMapping
    public ResponseEntity<?> listJobs() {
        try {
            List<JobStatus> jobs = jobService.listJobs();
            return ResponseEntity.ok(jobs);
        } catch (Exception e) {
            log.error("Error listing jobs", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<?> cancelJob(@PathVariable String jobId) {
        try {
            // Validate jobId format
            if (!jobId.matches("^[a-zA-Z0-9-]+$")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid job ID"));
            }

            jobService.cancelJob(jobId);
            return ResponseEntity.ok(Map.of("message", "Job cancelled"));

        } catch (Exception e) {
            log.error("Error cancelling job {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Internal server error", "requestId", java.util.UUID.randomUUID().toString()));
    }
}
