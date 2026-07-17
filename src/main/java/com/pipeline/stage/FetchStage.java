package com.pipeline.stage;

import com.pipeline.model.FetchResult;
import com.pipeline.model.FetchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FetchStage {
    private static final Logger log = LoggerFactory.getLogger(FetchStage.class);

    private static final int THREAD_POOL_SIZE = 10;
    private static final int HTTP_TIMEOUT_SEC = 5;
    private static final int CONTENT_SIZE_LIMIT_MB = 5;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int BACKPRESSURE_CHECK_INTERVAL_MS = 100;
    private static final int QUEUE_BACKPRESSURE_THRESHOLD = 5;

    private final ExecutorService executor = Executors.newFixedThreadPool(
        THREAD_POOL_SIZE,
        r -> {
            Thread t = new Thread(r, "FetchStage-" + Thread.currentThread().getId());
            t.setDaemon(false);
            return t;
        }
    );

    public void start(List<String> urls, BlockingQueue<FetchResult> stage2Queue) {
        log.info("Starting Fetch Stage with {} URLs", urls.size());

        for (int i = 0; i < urls.size(); i++) {
            int index = i;
            String url = urls.get(i);
            executor.submit(() -> fetchUrl(index, url, stage2Queue));
        }
    }

    private void fetchUrl(int index, String url, BlockingQueue<FetchResult> stage2Queue) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. Validate URL
            validateUrl(url);

            // 2. Apply backpressure: wait if Stage 2 queue is full
            applyBackpressure(stage2Queue);

            // 3. Fetch with timeout
            String content = fetchWithTimeout(url);

            // 4. Check content size
            if (content.length() > CONTENT_SIZE_LIMIT_MB * 1024 * 1024) {
                offerErrorResult(stage2Queue, index, url, FetchStatus.CONTENT_SIZE_EXCEEDED,
                    "Content exceeds " + CONTENT_SIZE_LIMIT_MB + "MB limit", startTime);
                return;
            }

            // 5. Offer successful result to Stage 2
            FetchResult result = new FetchResult(
                index,
                url,
                content,
                FetchStatus.SUCCESS,
                null,
                System.currentTimeMillis() - startTime
            );

            if (!stage2Queue.offer(result, 10, TimeUnit.SECONDS)) {
                log.warn("Failed to queue result for {} after timeout", url);
                offerErrorResult(stage2Queue, index, url, FetchStatus.FETCH_ERROR,
                    "Queue submission timeout", startTime);
            }

            log.debug("Fetched {}: {} bytes, {}ms", url, content.length(), System.currentTimeMillis() - startTime);

        } catch (SecurityException e) {
            offerErrorResult(stage2Queue, index, url, FetchStatus.INVALID_URL, e.getMessage(), startTime);
        } catch (IllegalArgumentException e) {
            offerErrorResult(stage2Queue, index, url, FetchStatus.INVALID_URL, e.getMessage(), startTime);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                offerErrorResult(stage2Queue, index, url, FetchStatus.FETCH_TIMEOUT,
                    "HTTP request timeout: " + e.getMessage(), startTime);
            } else {
                offerErrorResult(stage2Queue, index, url, FetchStatus.FETCH_ERROR,
                    "Fetch error: " + e.getMessage(), startTime);
            }
        }
    }

    private void validateUrl(String url) throws SecurityException, IllegalArgumentException {
        // 1. Null/empty check
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL is empty");
        }

        // 2. Length check
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("URL exceeds " + MAX_URL_LENGTH + " character limit");
        }

        // 3. Parse URL
        URL parsedUrl;
        try {
            parsedUrl = new URL(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL: " + e.getMessage());
        }

        // 4. Scheme validation: only HTTP(S)
        String scheme = parsedUrl.getProtocol();
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new SecurityException("Only HTTP(S) schemes allowed; got: " + scheme);
        }

        // 5. SSRF prevention: reject private/loopback IPs
        try {
            InetAddress addr = InetAddress.getByName(parsedUrl.getHost());
            if (addr.isLoopbackAddress()) {
                throw new SecurityException("Loopback address not allowed: " + parsedUrl.getHost());
            }
            if (addr.isSiteLocalAddress()) {
                throw new SecurityException("Private IP address not allowed: " + parsedUrl.getHost());
            }
            if (addr.isAnyLocalAddress() || addr.isLinkLocalAddress()) {
                throw new SecurityException("Internal/link-local address not allowed: " + parsedUrl.getHost());
            }
        } catch (Exception e) {
            if (e instanceof SecurityException) {
                throw (SecurityException) e;
            }
            throw new SecurityException("Cannot resolve hostname: " + parsedUrl.getHost());
        }
    }

    private void applyBackpressure(BlockingQueue<FetchResult> stage2Queue) throws InterruptedException {
        // ponytail: simple backpressure via polling; upgrade to Semaphore if contention measured
        while (stage2Queue.size() >= QUEUE_BACKPRESSURE_THRESHOLD) {
            log.debug("Stage 2 queue full ({}), waiting for backpressure...", stage2Queue.size());
            Thread.sleep(BACKPRESSURE_CHECK_INTERVAL_MS);
        }
    }

    private String fetchWithTimeout(String url) throws Exception {
        // ponytail: basic implementation; upgrade to HttpClient with proper timeout handling if needed
        java.net.URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_SEC * 1000);
        conn.setReadTimeout(HTTP_TIMEOUT_SEC * 1000);

        // Set User-Agent
        conn.setRequestProperty("User-Agent", "DocumentProcessor/1.0 (pipeline)");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        // Fetch content
        byte[] buffer = new byte[1024];
        int bytesRead;
        StringBuilder content = new StringBuilder();
        int totalBytes = 0;
        int maxBytes = CONTENT_SIZE_LIMIT_MB * 1024 * 1024;

        try (java.io.InputStream is = conn.getInputStream()) {
            while ((bytesRead = is.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > maxBytes) {
                    throw new IllegalArgumentException("Content exceeds size limit");
                }
                content.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
            }
        }

        return content.toString();
    }

    private void offerErrorResult(BlockingQueue<FetchResult> stage2Queue, int index, String url,
                                  FetchStatus status, String error, long startTime) {
        FetchResult errorResult = new FetchResult(
            index,
            url,
            null,
            status,
            error,
            System.currentTimeMillis() - startTime
        );

        try {
            if (!stage2Queue.offer(errorResult, 10, TimeUnit.SECONDS)) {
                log.warn("Failed to queue error result for {}", url);
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while queuing error result for {}", url, e);
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() throws InterruptedException {
        log.info("Shutting down Fetch Stage");
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Fetch Stage executor did not terminate within timeout");
            executor.shutdownNow();
        }
    }
}
