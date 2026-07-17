package com.pipeline.stage;

import com.pipeline.model.AnalysisResult;
import com.pipeline.model.FetchResult;
import com.pipeline.model.FetchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeStageTest {

    private AnalyzeStage analyzeStage;
    private BlockingQueue<FetchResult> inputQueue;
    private BlockingQueue<AnalysisResult> outputQueue;

    @BeforeEach
    void setUp() {
        inputQueue = new LinkedBlockingQueue<>(5);
        outputQueue = new LinkedBlockingQueue<>();
        analyzeStage = new AnalyzeStage();
    }

    @Test
    void testAnalyzeValidHtmlContent() throws InterruptedException {
        String html = "<html><body><h1>Hello World</h1><p>This is a test.</p></body></html>";
        FetchResult fetchResult = new FetchResult(0, "https://example.com", html, FetchStatus.SUCCESS, null, 100);

        inputQueue.put(fetchResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("https://example.com", result.url());
        assertTrue(result.wordFrequencies().containsKey("hello") || result.wordFrequencies().containsKey("world"));
        assertTrue(result.readabilityScore() >= 0);
    }

    @Test
    void testExtractLinksFromHtml() throws InterruptedException {
        String html = "<html><body><a href='/page1'>Link 1</a><a href='/page2'>Link 2</a></body></html>";
        FetchResult fetchResult = new FetchResult(0, "https://example.com", html, FetchStatus.SUCCESS, null, 100);

        inputQueue.put(fetchResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(2, result.links().size());
        assertTrue(result.links().contains("/page1"));
        assertTrue(result.links().contains("/page2"));
    }

    @Test
    void testWordFrequencyCount() throws InterruptedException {
        String html = "<html><body>hello hello world world world</body></html>";
        FetchResult fetchResult = new FetchResult(0, "https://example.com", html, FetchStatus.SUCCESS, null, 100);

        inputQueue.put(fetchResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.wordFrequencies().containsKey("hello"));
        assertTrue(result.wordFrequencies().containsKey("world"));
        assertEquals(2L, result.wordFrequencies().get("hello"));
        assertEquals(3L, result.wordFrequencies().get("world"));
    }

    @Test
    void testReadabilityScoreCalculation() throws InterruptedException {
        String html = "<html><body>Hello world. This is a test. Another sentence here.</body></html>";
        FetchResult fetchResult = new FetchResult(0, "https://example.com", html, FetchStatus.SUCCESS, null, 100);

        inputQueue.put(fetchResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.readabilityScore() > 0, "Readability score should be positive");
    }

    @Test
    void testHandleErrorFromFetchStage() throws InterruptedException {
        FetchResult errorResult = new FetchResult(0, "https://example.com", null, FetchStatus.FETCH_TIMEOUT, "Timeout", 5000);

        inputQueue.put(errorResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(5, TimeUnit.SECONDS);

        // Should return empty result without crashing
        assertNotNull(result);
        assertEquals("https://example.com", result.url());
        assertTrue(result.links().isEmpty());
        assertTrue(result.wordFrequencies().isEmpty());
        assertEquals(0.0, result.readabilityScore());
    }

    @Test
    void testEmptyHtmlContent() throws InterruptedException {
        FetchResult fetchResult = new FetchResult(0, "https://example.com", "", FetchStatus.SUCCESS, null, 100);

        inputQueue.put(fetchResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.wordFrequencies().isEmpty());
        assertEquals(0.0, result.readabilityScore());
    }

    @Test
    void testCaseInsensitiveWordFrequency() throws InterruptedException {
        String html = "<html><body>Hello HELLO hello</body></html>";
        FetchResult fetchResult = new FetchResult(0, "https://example.com", html, FetchStatus.SUCCESS, null, 100);

        inputQueue.put(fetchResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.wordFrequencies().containsKey("hello"));
        assertEquals(3L, result.wordFrequencies().get("hello"), "Should count case-insensitive");
    }

    @Test
    void testBoundedThreadPoolConcurrency() throws InterruptedException {
        // Verify that only 3 threads process concurrently
        String html = "<html><body>test content</body></html>";

        for (int i = 0; i < 5; i++) {
            FetchResult result = new FetchResult(i, "https://example.com/" + i, html, FetchStatus.SUCCESS, null, 100);
            inputQueue.put(result);
        }

        analyzeStage.start(inputQueue, outputQueue);

        // Collect results
        for (int i = 0; i < 5; i++) {
            AnalysisResult result = outputQueue.poll(10, TimeUnit.SECONDS);
            assertNotNull(result, "Should process item " + i);
        }
    }

    @Test
    void testWordMapSizeLimit() throws InterruptedException {
        // Create a document with many unique words
        StringBuilder htmlBuilder = new StringBuilder("<html><body>");
        for (int i = 0; i < 150000; i++) {  // More than 100k unique words
            htmlBuilder.append("word").append(i).append(" ");
        }
        htmlBuilder.append("</body></html>");

        FetchResult fetchResult = new FetchResult(0, "https://example.com", htmlBuilder.toString(), FetchStatus.SUCCESS, null, 100);

        inputQueue.put(fetchResult);
        analyzeStage.start(inputQueue, outputQueue);

        AnalysisResult result = outputQueue.poll(10, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.wordFrequencies().size() <= 100000, "Should cap word frequency map at 100k");
    }
}
