package com.pipeline.orchestration;

import com.pipeline.model.AnalysisResult;
import com.pipeline.model.JobAggregate;
import com.pipeline.stage.AnalyzeStage;
import com.pipeline.stage.FetchStage;
import com.pipeline.stage.StoreStage;
import com.pipeline.repository.JobRepository;
import com.pipeline.websocket.JobProgressHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.pipeline.model.FetchResult;

public class PipelineOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final String jobId;
    private final JobRepository repository;
    private final JobProgressHandler progressHandler;
    private final FetchStage stage1;
    private final AnalyzeStage stage2;
    private final StoreStage stage3;

    private final ScheduledExecutorService monitorExecutor = Executors.newScheduledThreadPool(1);

    public PipelineOrchestrator(String jobId, JobRepository repository) {
        this(jobId, repository, null);
    }

    public PipelineOrchestrator(String jobId, JobRepository repository, JobProgressHandler progressHandler) {
        this.jobId = jobId;
        this.repository = repository;
        this.progressHandler = progressHandler;
        this.stage1 = new FetchStage();
        this.stage2 = new AnalyzeStage();
        this.stage3 = new StoreStage(jobId, repository, progressHandler);
    }

    private Thread stage3Thread;

    /**
     * Starts the pipeline and returns a future that completes once every submitted URL
     * has reached a terminal state in Stage 3 (success or failure both count as processed).
     */
    public CompletableFuture<Void> start(List<String> urls) {
        log.info("Starting pipeline for job {} with {} URLs", jobId, urls.size());

        CompletableFuture<Void> completion = new CompletableFuture<>();
        int totalItems = urls.size();

        // Create inter-stage queues
        BlockingQueue<FetchResult> stage2InputQueue = stage2.getInputQueue();
        BlockingQueue<AnalysisResult> stage3InputQueue = new LinkedBlockingQueue<>();

        // Start Stage 2 (consumer of Stage 1, producer for Stage 3)
        stage2.start(stage2InputQueue, stage3InputQueue);

        // Start Stage 1 (producer, aware of Stage 2's queue for backpressure)
        stage1.start(urls, stage2InputQueue);

        // Start Stage 3 (consumer of Stage 2); completes `completion` once all items are done
        startStage3Worker(stage3InputQueue, totalItems, completion);

        // Monitor queue depths
        monitorExecutor.scheduleAtFixedRate(
            () -> logQueueDepths(stage2InputQueue),
            1, 1, TimeUnit.SECONDS
        );

        return completion;
    }

    private void startStage3Worker(BlockingQueue<AnalysisResult> stage3InputQueue, int totalItems,
                                    CompletableFuture<Void> completion) {
        // Stage 3 runs in a dedicated thread, pulling from stage3InputQueue and storing results
        stage3Thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    AnalysisResult result = stage3InputQueue.poll(1, TimeUnit.SECONDS);
                    if (result != null) {
                        stage3.processResult(result);
                    }

                    JobAggregate snapshot = stage3.getAggregate();
                    if (snapshot.documentsProcessed() + snapshot.documentsErrored() >= totalItems) {
                        completion.complete(null);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                log.debug("Stage 3 worker interrupted");
                Thread.currentThread().interrupt();
                completion.completeExceptionally(e);
            }
        }, "Stage3-Worker-" + jobId);

        stage3Thread.setDaemon(false);
        stage3Thread.start();
    }

    private void logQueueDepths(BlockingQueue<FetchResult> stage2InputQueue) {
        int depth = stage2InputQueue.size();
        log.debug("Pipeline {} - Stage 2 input queue size: {}/5", jobId, depth);
        if (progressHandler != null) {
            try {
                progressHandler.broadcastQueueDepth(jobId, depth);
            } catch (Exception e) {
                log.warn("Failed to broadcast queue depth: {}", e.getMessage());
            }
        }
    }

    public void shutdown() throws InterruptedException {
        log.info("Shutting down pipeline for job {}", jobId);

        monitorExecutor.shutdown();
        monitorExecutor.awaitTermination(5, TimeUnit.SECONDS);

        if (stage3Thread != null) {
            stage3Thread.interrupt();
            stage3Thread.join(5000);
        }

        stage1.shutdown();
        stage2.shutdown();
        stage3.shutdown();

        log.info("Pipeline {} shutdown complete", jobId);
    }
}
