package com.pipeline.stage;

import com.pipeline.model.AnalysisResult;
import com.pipeline.model.ItemStatus;
import com.pipeline.model.JobAggregate;
import com.pipeline.model.ProcessingStage;
import com.pipeline.model.ProcessingState;
import com.pipeline.repository.JobRepository;
import com.pipeline.websocket.JobProgressHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class StoreStage {
    private static final Logger log = LoggerFactory.getLogger(StoreStage.class);

    private final String jobId;
    private final JobRepository repository;
    private final JobProgressHandler progressHandler;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StoreStage-worker");
        t.setDaemon(false);
        return t;
    });

    private final JobAggregate aggregate = new JobAggregate();
    private final ReentrantReadWriteLock aggregateLock = new ReentrantReadWriteLock();

    public StoreStage(String jobId, JobRepository repository) {
        this(jobId, repository, null);
    }

    public StoreStage(String jobId, JobRepository repository, JobProgressHandler progressHandler) {
        this.jobId = jobId;
        this.repository = repository;
        this.progressHandler = progressHandler;
        this.aggregate.setStartTime(System.currentTimeMillis());
    }

    public void processResult(AnalysisResult result) {
        executor.submit(() -> {
            try {
                if (result.failed()) {
                    aggregateLock.writeLock().lock();
                    try {
                        aggregate.setDocumentsErrored(aggregate.documentsErrored() + 1);
                    } finally {
                        aggregateLock.writeLock().unlock();
                    }
                    broadcastItemFailed(result);
                    broadcastAggregate(getAggregate());
                    return;
                }

                // 1. Write to persistent storage
                repository.saveResult(jobId, result);

                // 2. Update aggregates (thread-safe with lock)
                updateAggregate(result);

                JobAggregate snapshot = getAggregate();

                // 3. Persist updated aggregate
                repository.updateAggregate(jobId, snapshot);

                // 4. Notify subscribers (item done + running aggregate)
                broadcastItemDone(result);
                broadcastAggregate(snapshot);

                log.debug("Stored result for job {}: {}", jobId, result.url());

            } catch (Exception e) {
                log.error("Error storing result for job {}: {}", jobId, e.getMessage(), e);
            }
        });
    }

    private void broadcastItemFailed(AnalysisResult result) {
        if (progressHandler == null) return;
        ItemStatus item = new ItemStatus();
        item.setIndex(result.index());
        item.setUrl(result.url());
        item.setStage(ProcessingStage.DONE);
        item.setState(ProcessingState.FAILED);
        item.setError(result.error());
        item.setEndTime(System.currentTimeMillis());
        try {
            progressHandler.broadcastItemUpdate(jobId, item);
        } catch (Exception e) {
            log.warn("Failed to broadcast item failure for job {}: {}", jobId, e.getMessage());
        }
    }

    private void broadcastItemDone(AnalysisResult result) {
        if (progressHandler == null) {
            return;
        }
        ItemStatus item = new ItemStatus();
        item.setIndex(result.index());
        item.setUrl(result.url());
        item.setStage(ProcessingStage.DONE);
        item.setState(ProcessingState.SUCCESS);
        item.setEndTime(System.currentTimeMillis());
        item.setReadabilityScore(result.readabilityScore());
        item.setWordCount(result.wordFrequencies().values().stream().mapToLong(Long::longValue).sum());
        item.setLinks(result.links());
        try {
            progressHandler.broadcastItemUpdate(jobId, item);
        } catch (Exception e) {
            log.warn("Failed to broadcast item update for job {}: {}", jobId, e.getMessage());
        }
    }

    private void broadcastAggregate(JobAggregate snapshot) {
        if (progressHandler == null) {
            return;
        }
        try {
            progressHandler.broadcastAggregateUpdate(jobId, snapshot);
        } catch (Exception e) {
            log.warn("Failed to broadcast aggregate update for job {}: {}", jobId, e.getMessage());
        }
    }

    private void updateAggregate(AnalysisResult result) {
        aggregateLock.writeLock().lock();
        try {
            aggregate.setDocumentsProcessed(aggregate.documentsProcessed() + 1);

            // Update average readability using Welford's algorithm for numerical stability
            double oldSum = aggregate.averageReadability() * (aggregate.documentsProcessed() - 1);
            double newSum = oldSum + result.readabilityScore();
            aggregate.setAverageReadability(newSum / aggregate.documentsProcessed());

            // Merge word frequencies into global top-words map
            long wordTotal = result.wordFrequencies().values().stream().mapToLong(Long::longValue).sum();
            aggregate.setTotalWordsAnalyzed(aggregate.totalWordsAnalyzed() + wordTotal);
            mergeWordFrequencies(result.wordFrequencies());

            aggregate.setLastUpdated(System.currentTimeMillis());

        } finally {
            aggregateLock.writeLock().unlock();
        }
    }

    private void mergeWordFrequencies(Map<String, Long> resultWords) {
        // Merge into global top-words map
        Map<String, Long> topWords = aggregate.topWords();
        if (topWords == null) {
            topWords = new HashMap<>();
            aggregate.setTopWords(topWords);
        }

        for (Map.Entry<String, Long> entry : resultWords.entrySet()) {
            topWords.merge(entry.getKey(), entry.getValue(), Long::sum);
        }

        // Keep only top 20 by frequency
        if (topWords.size() > 20) {
            // ponytail: sort and trim; upgrade to heap if performance matters
            aggregate.setTopWords(
                topWords.entrySet()
                    .stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(20)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            );
        }
    }

    public JobAggregate getAggregate() {
        aggregateLock.readLock().lock();
        try {
            // Return a deep copy to prevent external mutation
            JobAggregate copy = new JobAggregate();
            copy.setDocumentsProcessed(aggregate.documentsProcessed());
            copy.setDocumentsErrored(aggregate.documentsErrored());
            copy.setAverageReadability(aggregate.averageReadability());
            copy.setTotalWordsAnalyzed(aggregate.totalWordsAnalyzed());
            copy.setTopWords(new HashMap<>(aggregate.topWords()));
            copy.setStartTime(aggregate.startTime());
            copy.setLastUpdated(aggregate.lastUpdated());
            return copy;
        } finally {
            aggregateLock.readLock().unlock();
        }
    }

    public void shutdown() throws InterruptedException {
        log.info("Shutting down Store Stage for job {}", jobId);
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Store Stage executor did not terminate within timeout");
            executor.shutdownNow();
        }
    }
}
