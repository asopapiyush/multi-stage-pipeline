package com.pipeline.stage;

import com.pipeline.model.AnalysisResult;
import com.pipeline.model.FetchResult;
import com.pipeline.model.FetchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class AnalyzeStage {
    private static final Logger log = LoggerFactory.getLogger(AnalyzeStage.class);

    private static final int THREAD_POOL_SIZE = 3;
    private static final int INPUT_QUEUE_SIZE = 5;
    private static final int MAX_UNIQUE_WORDS = 100_000;
    private static final int MIN_WORD_LENGTH = 2;

    private final BlockingQueue<FetchResult> inputQueue = new LinkedBlockingQueue<>(INPUT_QUEUE_SIZE);
    private final ExecutorService executor = Executors.newFixedThreadPool(
        THREAD_POOL_SIZE,
        r -> {
            Thread t = new Thread(r, "AnalyzeStage-" + Thread.currentThread().getId());
            t.setDaemon(false);
            return t;
        }
    );

    public BlockingQueue<FetchResult> getInputQueue() {
        return inputQueue;
    }

    public void start(BlockingQueue<FetchResult> stage2InputQueue, BlockingQueue<AnalysisResult> stage3Queue) {
        log.info("Starting Analyze Stage with {} thread pool size", THREAD_POOL_SIZE);

        // Start worker threads
        for (int i = 0; i < THREAD_POOL_SIZE; i++) {
            executor.submit(() -> analyzeWorker(stage2InputQueue, stage3Queue));
        }
    }

    private void analyzeWorker(BlockingQueue<FetchResult> inputQ, BlockingQueue<AnalysisResult> outputQ) {
        try {
            while (true) {
                // Block until an item is available
                FetchResult fetchResult = inputQ.take();

                if (fetchResult.url() == null || "POISON".equals(fetchResult.url())) {
                    // Poison pill to stop worker
                    log.debug("Analyze worker received poison pill, exiting");
                    break;
                }

                try {
                    AnalysisResult analysis = analyzeContent(fetchResult);
                    outputQ.put(analysis);
                    log.debug("Analyzed {}: {} links, {} unique words", fetchResult.url(), analysis.links().size(), analysis.wordFrequencies().size());
                } catch (Exception e) {
                    log.error("Error analyzing {}: {}", fetchResult.url(), e.getMessage());
                    // Return empty result to keep pipeline flowing
                    AnalysisResult emptyResult = new AnalysisResult(
                        fetchResult.index(),
                        fetchResult.url(),
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        0.0
                    );
                    outputQ.put(emptyResult);
                }
            }
        } catch (InterruptedException e) {
            log.error("Analyze worker interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private AnalysisResult analyzeContent(FetchResult fetchResult) {
        // If fetch failed, return empty analysis marked as failed
        if (fetchResult.status() != FetchStatus.SUCCESS || fetchResult.content() == null) {
            return new AnalysisResult(
                fetchResult.index(),
                fetchResult.url(),
                Collections.emptyList(),
                Collections.emptyMap(),
                0.0,
                true,
                fetchResult.error()
            );
        }

        try {
            String content = fetchResult.content();

            // 1. Extract links using jsoup (safe, no ReDoS)
            List<String> links = extractLinks(content);

            // 2. Extract plain text
            String plainText = extractPlainText(content);

            // 3. Compute word frequencies
            Map<String, Long> wordFreq = computeWordFrequencies(plainText);

            // 4. Compute readability score
            double readability = computeReadabilityScore(plainText);

            return new AnalysisResult(
                fetchResult.index(),
                fetchResult.url(),
                links,
                wordFreq,
                readability
            );

        } catch (Exception e) {
            log.error("Error analyzing content for {}: {}", fetchResult.url(), e.getMessage());
            return new AnalysisResult(
                fetchResult.index(),
                fetchResult.url(),
                Collections.emptyList(),
                Collections.emptyMap(),
                0.0
            );
        }
    }

    private List<String> extractLinks(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent, "", org.jsoup.parser.Parser.htmlParser());
            return doc.select("a[href]")
                .stream()
                .map(el -> el.attr("href"))
                .filter(href -> !href.isEmpty())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Error extracting links: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String extractPlainText(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent, "", org.jsoup.parser.Parser.htmlParser());
            return doc.body().text();
        } catch (Exception e) {
            log.warn("Error extracting plain text: {}", e.getMessage());
            return "";
        }
    }

    private Map<String, Long> computeWordFrequencies(String text) {
        Map<String, Long> freq = new HashMap<>();

        if (text == null || text.isEmpty()) {
            return freq;
        }

        String[] words = text.toLowerCase().split("\\s+");

        for (String word : words) {
            // Only alphabetic words, min length
            if (word.matches("^[a-z]+$") && word.length() >= MIN_WORD_LENGTH) {
                // Hard cap: stop adding new words at 100k unique entries
                if (freq.size() >= MAX_UNIQUE_WORDS && !freq.containsKey(word)) {
                    log.warn("Word frequency map hit {} unique word limit", MAX_UNIQUE_WORDS);
                    continue;  // Skip this word, don't add new entry
                }

                freq.merge(word, 1L, Long::sum);
            }
        }

        return freq;
    }

    private double computeReadabilityScore(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }

        // ponytail: simple readability formula (avg_sentence_len * avg_word_len)
        // upgrade to Flesch-Kincaid if needed for real metrics

        // Sentence count: split on .!?
        int sentenceCount = 0;
        for (char c : text.toCharArray()) {
            if (c == '.' || c == '!' || c == '?') {
                sentenceCount++;
            }
        }
        sentenceCount = Math.max(1, sentenceCount);

        // Word count and total chars
        String[] words = text.split("\\s+");
        int wordCount = 0;
        int charCount = 0;

        for (String word : words) {
            if (word.matches("^[a-zA-Z]+$")) {
                wordCount++;
                charCount += word.length();
            }
        }

        if (wordCount == 0) {
            return 0.0;
        }

        double avgSentenceLen = (double) wordCount / sentenceCount;
        double avgWordLen = (double) charCount / wordCount;

        return avgSentenceLen * avgWordLen;
    }

    public void shutdown() throws InterruptedException {
        log.info("Shutting down Analyze Stage");
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Analyze Stage executor did not terminate within timeout");
            executor.shutdownNow();
        }
    }
}
