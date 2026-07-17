# Task Breakdown: Multi-Stage Parallel Document Processing Pipeline

**Total Duration:** 90 minutes  
**Phases:** 5 (Foundation → Pipeline → API → Frontend → Testing)  
**Status Tracking:** Use checkboxes to track completion

---

## PHASE 1: Foundation (20 minutes)

Establish project skeleton, data models, and persistence layer.

### T1.1 — Spring Boot Project Setup (5 min)

**Objective:** Create runnable Maven/Gradle project with required dependencies.

**Deliverables:**
- [ ] Spring Boot 3.x project (use Spring Initializr or Maven archetype)
- [ ] POM.xml with dependencies:
  - `spring-boot-starter-web` (REST, Tomcat)
  - `spring-boot-starter-websocket` (WebSocket support)
  - `org.jsoup:jsoup` (HTML parsing, v1.15+)
  - `org.sqlite:sqlite-jdbc` (SQLite driver)
  - `com.fasterxml.jackson.core:jackson-databind` (JSON)
  - `junit-jupiter` + `spring-boot-starter-test` (testing)
- [ ] Application runs on `localhost:8080`
- [ ] Gradle or Maven wrapper configured

**Acceptance Criteria:**
```bash
mvn clean package
java -jar target/pipeline-*.jar
# Opens http://localhost:8080 → responds
```

**Notes:**
- Use Java 17+ (LTS)
- Keep packaging simple (JAR, embedded Tomcat)

---

### T1.2 — Data Models & DTOs (8 min)

**Objective:** Define data structures for end-to-end flow.

**Deliverables:**
Create Java record/class definitions for:

1. **JobRequest** (input to API)
   ```java
   record JobRequest(List<String> urls) {}
   ```

2. **FetchResult** (Stage 1 output)
   ```java
   class FetchResult {
     String url;
     String content;      // plain text or HTML body
     FetchStatus status;  // SUCCESS, FETCH_TIMEOUT, INVALID_URL, CONTENT_SIZE_EXCEEDED
     String error;        // error message if status != SUCCESS
     long fetchTimeMs;
   }
   ```

3. **AnalysisResult** (Stage 2 output)
   ```java
   class AnalysisResult {
     String url;
     List<String> links;                   // extracted hrefs
     Map<String, Long> wordFrequencies;   // word → count
     double readabilityScore;             // (avg_sentence_len * avg_word_len)
     int sentenceCount;
     int wordCount;
   }
   ```

4. **JobAggregate** (running state, Stage 3 maintains)
   ```java
   class JobAggregate {
     int documentsProcessed;
     int documentsErrored;
     double averageReadability;
     long totalWordsAnalyzed;
     Map<String, Long> topWords;          // top 20 words, sorted by frequency
     long startTime;
     long lastUpdated;
   }
   ```

5. **JobStatus** (query response)
   ```java
   class JobStatus {
     String jobId;
     JobState state;                      // PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
     List<ItemStatus> items;              // per-URL status
     JobAggregate aggregates;
     long createdAt;
     long updatedAt;
   }
   ```

6. **ItemStatus** (one entry per URL)
   ```java
   class ItemStatus {
     int index;
     String url;
     ProcessingStage stage;               // QUEUED, FETCHING, ANALYZING, STORING, DONE
     ProcessingState state;               // PENDING, IN_PROGRESS, SUCCESS, FAILED
     String error;                        // if FAILED
     long startTime;
     long endTime;
   }
   ```

7. **StreamMessage** (WebSocket event)
   ```java
   class StreamMessage {
     String eventType;                    // "item_update", "aggregate_update", "job_complete"
     String jobId;
     ItemStatus itemStatus;               // if eventType = item_update
     JobAggregate aggregates;             // if eventType = aggregate_update
     long timestamp;
   }
   ```

**Acceptance Criteria:**
- [ ] All classes compile without errors
- [ ] Jackson can serialize/deserialize to JSON
- [ ] No circular dependencies

**Notes:**
- Use Lombok `@Data` or records for boilerplate reduction
- `enum ProcessingStage { QUEUED, FETCHING, ANALYZING, STORING, DONE }`
- `enum ProcessingState { PENDING, IN_PROGRESS, SUCCESS, FAILED }`

---

### T1.3 — Persistence Layer (7 min)

**Objective:** Set up thread-safe storage (SQLite + JSON or pure JSON).

**Deliverables:**

**Option A: SQLite**
```sql
CREATE TABLE jobs (
  id TEXT PRIMARY KEY,
  state TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  docs_processed INTEGER DEFAULT 0,
  docs_errored INTEGER DEFAULT 0,
  avg_readability REAL DEFAULT 0
);

CREATE TABLE job_items (
  id INTEGER PRIMARY KEY,
  job_id TEXT NOT NULL,
  url TEXT NOT NULL,
  stage TEXT,
  state TEXT,
  error TEXT,
  started_at INTEGER,
  ended_at INTEGER,
  FOREIGN KEY(job_id) REFERENCES jobs(id)
);

CREATE TABLE job_results (
  id INTEGER PRIMARY KEY,
  job_id TEXT NOT NULL,
  url TEXT NOT NULL,
  content TEXT,          -- fetched content
  links TEXT,            -- JSON array of links
  word_freq TEXT,        -- JSON map
  readability_score REAL,
  FOREIGN KEY(job_id) REFERENCES jobs(id)
);

CREATE TABLE job_aggregates (
  job_id TEXT PRIMARY KEY,
  top_words TEXT,        -- JSON map, top 20
  total_words_analyzed INTEGER,
  last_updated INTEGER,
  FOREIGN KEY(job_id) REFERENCES jobs(id)
);
```

**Option B: JSON File-based**
```
data/
  ├─ jobs.json          (array of job summaries)
  └─ jobs/
     ├─ abc-123.json    (job detail + results + aggregates)
     └─ xyz-789.json
```

**DAO/Repository Class:**
```java
interface JobRepository {
  void createJob(String jobId, JobStatus status);
  void updateJobItem(String jobId, ItemStatus item);
  void saveResult(String jobId, AnalysisResult result);
  void updateAggregate(String jobId, JobAggregate agg);
  JobStatus getJob(String jobId);
  List<JobStatus> listJobs();
}
```

**Implementation Requirements:**
- [ ] Thread-safe writes (synchronized or write lock)
- [ ] Prepared statements (if SQLite)
- [ ] No hardcoded paths; use config
- [ ] Tests: can insert and read job status

**Acceptance Criteria:**
```java
// Usage
repo.createJob("job-1", new JobStatus(...));
repo.updateJobItem("job-1", new ItemStatus(...));
JobStatus retrieved = repo.getJob("job-1");
assert retrieved.jobId.equals("job-1");
```

**Notes:**
- For 90-min scope, SQLite is faster to implement
- Ensure all DB operations use try-with-resources for connection cleanup
- Add database initialization on startup (create tables if not exist)

---

## PHASE 2: Pipeline Architecture (30 minutes)

Implement three concurrent stages with proper handoffs and backpressure.

### T2.1 — Stage 1 Executor: Fetch (10 min)

**Objective:** Fetch URLs concurrently (10 concurrent), with backpressure to Stage 2.

**Deliverables:**

**FetchStage Class:**
```java
class FetchStage {
  private final ExecutorService executor = 
    Executors.newFixedThreadPool(10, new ThreadFactory() { ... });
  private final BlockingQueue<FetchResult> outputQueue;
  private final int stage2MaxQueueSize = 5;
  private final Semaphore backpressureSemaphore;
  
  void start(List<String> urls, BlockingQueue<FetchResult> stage2Queue) {
    for (int i = 0; i < urls.size(); i++) {
      executor.submit(() -> fetchUrl(urls.get(i), stage2Queue));
    }
  }
  
  private void fetchUrl(String url, BlockingQueue<FetchResult> stage2Queue) {
    try {
      // 1. Validate URL
      validateUrl(url);
      
      // 2. Check backpressure: is Stage 2 queue full?
      while (stage2Queue.size() >= stage2MaxQueueSize) {
        Thread.sleep(100);  // or use Semaphore.acquire()
      }
      
      // 3. Fetch with timeout
      String content = fetchWithTimeout(url, Duration.ofSeconds(5));
      
      // 4. Offer to Stage 2 queue
      FetchResult result = new FetchResult(url, content, FETCH_SUCCESS, null);
      stage2Queue.offer(result);
      
    } catch (SecurityException e) {
      offerError(stage2Queue, url, INVALID_URL, e.getMessage());
    } catch (TimeoutException e) {
      offerError(stage2Queue, url, FETCH_TIMEOUT, e.getMessage());
    } catch (Exception e) {
      offerError(stage2Queue, url, FETCH_ERROR, e.getMessage());
    }
  }
  
  private void validateUrl(String url) throws SecurityException {
    // No file://, gopher://, etc.
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw new SecurityException("Only HTTP(S) schemes allowed");
    }
    // Optional: domain whitelist
    // Optional: reject internal IPs (127.0.0.1, 192.168.*, etc.)
  }
  
  private String fetchWithTimeout(String url, Duration timeout) 
      throws TimeoutException, IOException {
    RestTemplate restTemplate = new RestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.set("User-Agent", "DocumentProcessor/1.0");
    
    try {
      ResponseEntity<String> response = restTemplate.exchange(
        url, HttpMethod.GET, new HttpEntity<>(headers), String.class
      );
      
      String content = response.getBody();
      if (content.length() > 5 * 1024 * 1024) {  // 5MB
        throw new IllegalArgumentException("Content size exceeds 5MB");
      }
      return content;
    } catch (HttpClientErrorException | HttpServerErrorException e) {
      throw new IOException("HTTP " + e.getRawStatusCode() + ": " + e.getMessage());
    }
    // TODO: implement actual timeout using AsyncRestTemplate or OkHttp
  }
  
  void shutdown() {
    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);
  }
}
```

**Implementation Details:**
- [ ] URL validation: scheme, no file://, optional domain whitelist
- [ ] HTTP fetch with socket timeout (5s)
- [ ] Content size check (reject > 5MB)
- [ ] Extract plain text (strip HTML tags, or use jsoup)
- [ ] Error handling: mark as FETCH_ERROR, don't block others
- [ ] Backpressure: monitor Stage 2 queue before offering
- [ ] Logging: log queue depth every 1 sec (DEBUG)

**Acceptance Criteria:**
```java
// Test
List<String> urls = List.of(
  "https://example.com",
  "https://en.wikipedia.org/wiki/Concurrency_(computer_science)"
);
FetchStage stage1 = new FetchStage();
BlockingQueue<FetchResult> stage2Queue = new LinkedBlockingQueue<>(5);
stage1.start(urls, stage2Queue);

// Should produce FetchResult entries in stage2Queue
Thread.sleep(10000);
assert stage2Queue.size() > 0;
```

---

### T2.2 — Stage 2 Executor: Analyze (10 min)

**Objective:** Analyze fetched content (extract links, word frequency, readability) with 3 concurrent workers and bounded queue.

**Deliverables:**

**AnalyzeStage Class:**
```java
class AnalyzeStage {
  private final ExecutorService executor = 
    Executors.newFixedThreadPool(3, ...);
  private final BlockingQueue<FetchResult> inputQueue = 
    new LinkedBlockingQueue<>(5);  // Bounded!
  private final BlockingQueue<AnalysisResult> outputQueue;
  
  void start(BlockingQueue<AnalysisResult> stage3Queue) {
    for (int i = 0; i < 3; i++) {
      executor.submit(() -> {
        while (true) {
          FetchResult result = inputQueue.take();  // Blocks if empty
          if (result.isPoison()) break;
          
          AnalysisResult analysis = analyzeContent(result);
          stage3Queue.put(analysis);
        }
      });
    }
  }
  
  public BlockingQueue<FetchResult> getInputQueue() {
    return inputQueue;
  }
  
  private AnalysisResult analyzeContent(FetchResult fetch) {
    try {
      if (fetch.status != FetchStatus.SUCCESS) {
        return new AnalysisResult(fetch.url, Collections.emptyList(), 
                                  new HashMap<>(), 0.0);  // empty result
      }
      
      String content = fetch.content;
      
      // 1. Extract links (HTML parsing)
      List<String> links = extractLinks(content);
      
      // 2. Extract text and compute metrics
      String plainText = extractPlainText(content);
      Map<String, Long> wordFreq = computeWordFrequencies(plainText);
      double readability = computeReadabilityScore(plainText);
      
      return new AnalysisResult(fetch.url, links, wordFreq, readability);
      
    } catch (Exception e) {
      // Return partial result on error
      return new AnalysisResult(fetch.url, Collections.emptyList(), 
                                new HashMap<>(), 0.0);
    }
  }
  
  private List<String> extractLinks(String htmlContent) {
    Document doc = Jsoup.parse(htmlContent);
    return doc.select("a[href]")
              .stream()
              .map(el -> el.attr("href"))
              .filter(href -> !href.isEmpty())
              .collect(Collectors.toList());
  }
  
  private String extractPlainText(String htmlContent) {
    Document doc = Jsoup.parse(htmlContent);
    return doc.body().text();
  }
  
  private Map<String, Long> computeWordFrequencies(String text) {
    Map<String, Long> freq = new HashMap<>();
    
    String[] words = text.toLowerCase()
                         .split("\\s+");
    
    for (String word : words) {
      // Only alphabetic words, min length 2
      if (word.matches("^[a-z]+$") && word.length() >= 2) {
        freq.merge(word, 1L, Long::sum);
      }
    }
    
    // Cap at 100k unique words (prevent memory explosion)
    if (freq.size() > 100_000) {
      freq = freq.entrySet()
                 .stream()
                 .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                 .limit(100_000)
                 .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    
    return freq;
  }
  
  private double computeReadabilityScore(String text) {
    // Readability = (avg_sentence_length) * (avg_word_length)
    
    // Sentence count: split on .!?
    int sentenceCount = Math.max(1, text.split("[.!?]+").length - 1);
    
    // Word count and total characters
    String[] words = text.split("\\s+");
    int wordCount = (int) Arrays.stream(words)
                                .filter(w -> w.matches("^[a-z]+$"))
                                .count();
    
    if (wordCount == 0) return 0.0;
    
    int totalChars = (int) Arrays.stream(words)
                                 .filter(w -> w.matches("^[a-z]+$"))
                                 .mapToInt(String::length)
                                 .sum();
    
    double avgSentenceLen = (double) wordCount / sentenceCount;
    double avgWordLen = (double) totalChars / wordCount;
    
    return avgSentenceLen * avgWordLen;
  }
  
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);
  }
}
```

**Implementation Details:**
- [ ] Input queue: LinkedBlockingQueue(5) — bounded!
- [ ] HTML parsing: jsoup (safe, no ReDoS)
- [ ] Extract plain text (ignore HTML tags)
- [ ] Extract links: `a[href]` selector
- [ ] Word frequency: split on whitespace, filter alphabetic, case-insensitive
- [ ] Readability score: formula as specified (sentence_len * word_len)
- [ ] Error handling: return empty result if analysis fails (don't propagate)
- [ ] Memory limit: cap word map at 100k entries per document

**Acceptance Criteria:**
```java
FetchResult html = new FetchResult(
  "https://example.com",
  "<html><body><h1>Hello World</h1><a href='/page'>link</a></body></html>",
  SUCCESS, null
);

AnalysisResult analysis = analyzeContent(html);
assert analysis.links.contains("/page");
assert analysis.wordFrequencies.get("hello") == 1;
```

---

### T2.3 — Stage 3 Executor: Store & Aggregate (7 min)

**Objective:** Sequentially write results, maintain thread-safe aggregates.

**Deliverables:**

**StoreStage Class:**
```java
class StoreStage {
  private final ExecutorService executor = 
    Executors.newSingleThreadExecutor();  // Single thread = sequential
  private final JobRepository repository;
  private final String jobId;
  private final JobAggregate aggregate;  // shared state
  private final ReadWriteLock aggregateLock = new ReentrantReadWriteLock();
  
  StoreStage(String jobId, JobRepository repository) {
    this.jobId = jobId;
    this.repository = repository;
    this.aggregate = new JobAggregate();
    this.aggregate.startTime = System.currentTimeMillis();
  }
  
  public void processResult(AnalysisResult result) {
    executor.submit(() -> {
      try {
        // 1. Write to persistent storage
        repository.saveResult(jobId, result);
        
        // 2. Update aggregates (locked write)
        aggregateLock.writeLock().lock();
        try {
          aggregate.documentsProcessed++;
          
          // Update average readability
          double sumBefore = aggregate.averageReadability * (aggregate.documentsProcessed - 1);
          aggregate.averageReadability = (sumBefore + result.readabilityScore) / aggregate.documentsProcessed;
          
          // Merge word frequencies into global top-20
          mergeWordFrequencies(result.wordFrequencies);
          
          aggregate.lastUpdated = System.currentTimeMillis();
        } finally {
          aggregateLock.writeLock().unlock();
        }
        
        // 3. Update DB
        repository.updateAggregate(jobId, aggregate);
        
      } catch (Exception e) {
        log.error("Error storing result for job {}: {}", jobId, e.getMessage());
      }
    });
  }
  
  private void mergeWordFrequencies(Map<String, Long> resultWords) {
    // Merge into global top-words map
    if (aggregate.topWords == null) {
      aggregate.topWords = new HashMap<>();
    }
    
    for (Map.Entry<String, Long> entry : resultWords.entrySet()) {
      aggregate.topWords.merge(entry.getKey(), entry.getValue(), Long::sum);
    }
    
    // Keep only top 20 by frequency
    if (aggregate.topWords.size() > 20) {
      aggregate.topWords = aggregate.topWords.entrySet()
                                             .stream()
                                             .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                                             .limit(20)
                                             .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
  }
  
  public JobAggregate getAggregate() {
    aggregateLock.readLock().lock();
    try {
      return deepCopy(aggregate);  // return snapshot
    } finally {
      aggregateLock.readLock().unlock();
    }
  }
  
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);
  }
}
```

**Implementation Details:**
- [ ] Single-threaded executor: sequential writes, no race conditions
- [ ] Thread-safe aggregate: ReadWriteLock or ConcurrentHashMap
- [ ] DB writes: prepared statements, no SQL injection
- [ ] Top-20 words: keep sorted, trim periodically
- [ ] Running average: compute incrementally (sum update, divide by count)

**Acceptance Criteria:**
```java
StoreStage stage3 = new StoreStage("job-1", repo);

AnalysisResult result1 = new AnalysisResult(...);
stage3.processResult(result1);

Thread.sleep(1000);
JobAggregate agg = stage3.getAggregate();
assert agg.documentsProcessed == 1;
```

---

### T2.4 — Backpressure Integration (3 min)

**Objective:** Wire Stages 1, 2, 3 together with proper queue handoffs.

**Deliverables:**

**PipelineOrchestrator Class:**
```java
class PipelineOrchestrator {
  private final FetchStage stage1;
  private final AnalyzeStage stage2;
  private final StoreStage stage3;
  
  PipelineOrchestrator(String jobId, JobRepository repo) {
    this.stage1 = new FetchStage();
    this.stage2 = new AnalyzeStage();
    this.stage3 = new StoreStage(jobId, repo);
  }
  
  void start(List<String> urls) {
    // Start Stage 3 first (consumer)
    BlockingQueue<AnalysisResult> stage3InputQueue = new LinkedBlockingQueue<>();
    stage3.start(stage3InputQueue);
    
    // Start Stage 2 (consumer of Stage 1, producer for Stage 3)
    stage2.start(stage3InputQueue);
    
    // Start Stage 1 (producer, aware of Stage 2's queue for backpressure)
    stage1.start(urls, stage2.getInputQueue());
    
    // Log queue depths periodically (DEBUG)
    ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1);
    monitor.scheduleAtFixedRate(() -> {
      log.debug("Stage 2 input queue size: {}", stage2.getInputQueue().size());
    }, 1, 1, TimeUnit.SECONDS);
  }
}
```

**Data Flow (Backpressure):**
```
Stage 1 fetches:
  ├─ Before offering to Stage 2 queue
  ├─ Check: stage2Queue.size() >= 5?
  ├─ If YES: wait (Thread.sleep or Semaphore)
  └─ If NO: offer result

Stage 2 analyzes (threads pull from inputQueue):
  ├─ BlockingQueue.take() blocks if queue empty
  └─ Auto-backpressure: if Stage 1 fills queue, Stage 1.submit() blocks

Stage 3 stores (single thread):
  ├─ Processes Stage 2 output sequentially
  └─ Updates aggregates under lock
```

**Acceptance Criteria:**
- [ ] Verify queue doesn't grow unbounded
- [ ] Log output shows Stage 2 queue size staying ≤ 5
- [ ] Stage 1 doesn't get ahead of Stages 2 & 3

---

## PHASE 3: REST API & Streaming (20 minutes)

Expose pipeline via HTTP endpoints and WebSocket.

### T3.1 — POST /jobs Endpoint (5 min)

**Objective:** Accept URL batch, start pipeline, return job ID.

**Deliverables:**

**Controller:**
```java
@RestController
@RequestMapping("/api/jobs")
class JobController {
  private final JobService jobService;
  
  @PostMapping
  ResponseEntity<Map<String, String>> createJob(@RequestBody JobRequest req) {
    // Validate input
    if (req.urls().isEmpty() || req.urls().size() > 1000) {
      return ResponseEntity.badRequest().build();
    }
    
    // Create job record
    String jobId = UUID.randomUUID().toString();
    jobService.startJob(jobId, req.urls());
    
    return ResponseEntity.ok(Map.of("jobId", jobId));
  }
}
```

**JobService:**
```java
@Service
class JobService {
  private final JobRepository repo;
  private final Map<String, PipelineOrchestrator> jobs = new ConcurrentHashMap<>();
  
  void startJob(String jobId, List<String> urls) {
    // Create DB record
    repo.createJob(jobId, new JobStatus(jobId, RUNNING, ...));
    
    // Start pipeline async
    CompletableFuture.runAsync(() -> {
      PipelineOrchestrator pipeline = new PipelineOrchestrator(jobId, repo);
      jobs.put(jobId, pipeline);
      pipeline.start(urls);
    });
  }
}
```

**Acceptance Criteria:**
- [ ] POST /api/jobs with `{"urls": ["url1", "url2"]}` returns `{"jobId": "..."}`
- [ ] Job ID is UUID format
- [ ] Pipeline starts asynchronously
- [ ] DB record created with state = RUNNING

---

### T3.2 — GET /jobs/:id Endpoint (4 min)

**Objective:** Return current job state (items + aggregates).

**Deliverables:**

```java
@GetMapping("/{jobId}")
ResponseEntity<JobStatus> getJob(@PathVariable String jobId) {
  Optional<JobStatus> job = jobService.getJob(jobId);
  return job.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
}
```

**Response Example:**
```json
{
  "jobId": "abc-123",
  "state": "RUNNING",
  "items": [
    {
      "index": 0,
      "url": "https://example.com",
      "stage": "ANALYZING",
      "state": "IN_PROGRESS",
      "startTime": 1626...,
      "endTime": null
    }
  ],
  "aggregates": {
    "documentsProcessed": 3,
    "averageReadability": 25.5,
    "topWords": [
      {"word": "concurrency", "count": 45},
      ...
    ]
  },
  "updatedAt": 1626...
}
```

**Acceptance Criteria:**
- [ ] GET /api/jobs/:id returns full job status
- [ ] Aggregates update as pipeline progresses
- [ ] Returns 404 if job not found

---

### T3.3 — WebSocket/SSE on /jobs/:id/stream (8 min)

**Objective:** Stream real-time progress to all connected clients.

**Deliverables:**

**WebSocket Configuration:**
```java
@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {
  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(new JobProgressHandler(), "/api/jobs/*/stream")
            .setAllowedOrigins("*");
  }
}
```

**WebSocket Handler:**
```java
@Component
class JobProgressHandler extends TextWebSocketHandler {
  private final JobService jobService;
  private final Map<String, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();
  
  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String jobId = extractJobIdFromUrl(session.getUri());
    subscriptions.computeIfAbsent(jobId, k -> ConcurrentHashMap.newKeySet())
                 .add(session);
  }
  
  public void broadcastItemUpdate(String jobId, ItemStatus item) throws IOException {
    StreamMessage msg = new StreamMessage("item_update", jobId, item, null, System.currentTimeMillis());
    String json = objectMapper.writeValueAsString(msg);
    
    Set<WebSocketSession> sessions = subscriptions.getOrDefault(jobId, Collections.emptySet());
    for (WebSocketSession session : sessions) {
      if (session.isOpen()) {
        session.sendMessage(new TextMessage(json));
      }
    }
  }
  
  public void broadcastAggregateUpdate(String jobId, JobAggregate agg) throws IOException {
    StreamMessage msg = new StreamMessage("aggregate_update", jobId, null, agg, System.currentTimeMillis());
    String json = objectMapper.writeValueAsString(msg);
    
    // Broadcast to all subscribed clients
    Set<WebSocketSession> sessions = subscriptions.getOrDefault(jobId, Collections.emptySet());
    for (WebSocketSession session : sessions) {
      if (session.isOpen()) {
        session.sendMessage(new TextMessage(json));
      }
    }
  }
}
```

**Integration with Pipeline:**
- When Stage 1 completes fetch: emit item_update (stage=ANALYZING)
- When Stage 2 completes analysis: emit item_update (stage=STORING)
- When Stage 3 stores result: emit item_update (stage=DONE) + aggregate_update

**Frontend WebSocket Client (JavaScript):**
```javascript
const ws = new WebSocket(`ws://localhost:8080/api/jobs/${jobId}/stream`);

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  if (msg.eventType === 'item_update') {
    updateItemRow(msg.itemStatus);
  } else if (msg.eventType === 'aggregate_update') {
    updateAggregateDisplay(msg.aggregates);
  }
};
```

**Acceptance Criteria:**
- [ ] WebSocket connects to /api/jobs/:id/stream
- [ ] Item updates streamed as stage progresses
- [ ] Aggregate updates streamed
- [ ] Multiple clients can subscribe to same job
- [ ] Frontend displays updates in real-time

---

### T3.4 — GET /jobs Endpoint (3 min)

**Objective:** List all past jobs with summary stats.

**Deliverables:**

```java
@GetMapping
ResponseEntity<List<JobSummary>> listJobs() {
  return ResponseEntity.ok(jobService.listJobs());
}

record JobSummary(
  String jobId,
  JobState state,
  int totalItems,
  int itemsComplete,
  int itemsErrored,
  double avgReadability,
  long createdAt,
  long duration
) {}
```

**Response Example:**
```json
[
  {
    "jobId": "abc-123",
    "state": "COMPLETED",
    "totalItems": 15,
    "itemsComplete": 14,
    "itemsErrored": 1,
    "avgReadability": 26.3,
    "createdAt": 1626...,
    "duration": 45000
  }
]
```

**Acceptance Criteria:**
- [ ] GET /api/jobs returns array of job summaries
- [ ] Can paginate if many jobs (optional)

---

## PHASE 4: Frontend (15 minutes)

Build simple HTML/CSS/JS UI for submission and progress display.

### T4.1 — HTML Form & Layout (5 min)

**Objective:** Input form for URLs, job ID display, progress table.

**Deliverables:**

```html
<!DOCTYPE html>
<html>
<head>
  <title>Document Pipeline</title>
  <style>
    body { font-family: sans-serif; margin: 20px; }
    textarea { width: 100%; height: 150px; }
    table { border-collapse: collapse; width: 100%; margin-top: 20px; }
    th, td { border: 1px solid #ccc; padding: 8px; text-align: left; }
    th { background: #f5f5f5; }
    .queue-indicator { display: inline-block; width: 20px; height: 20px; margin: 5px; border-radius: 50%; }
    .queue-indicator.full { background: red; }
    .queue-indicator.partial { background: orange; }
    .queue-indicator.empty { background: green; }
  </style>
</head>
<body>
  <h1>Document Processing Pipeline</h1>
  
  <div id="form-section">
    <h2>Submit URLs</h2>
    <textarea id="urlInput" placeholder="https://example.com&#10;https://another.com"></textarea>
    <button onclick="submitJob()">Process</button>
  </div>
  
  <div id="progress-section" style="display:none;">
    <h2>Progress</h2>
    <p>Job ID: <strong id="jobId"></strong></p>
    
    <h3>Queue Status</h3>
    <div>Stage 2 Queue: <span id="stage2QueueSize"></span>/5 
      <span id="stage2QueueIndicator" class="queue-indicator"></span>
    </div>
    
    <h3>Processing Items</h3>
    <table>
      <thead>
        <tr>
          <th>URL</th>
          <th>Stage</th>
          <th>Status</th>
          <th>Error</th>
        </tr>
      </thead>
      <tbody id="itemsTable">
      </tbody>
    </table>
    
    <h3>Aggregates</h3>
    <div>
      <p>Documents Processed: <span id="docsProcessed">0</span></p>
      <p>Average Readability: <span id="avgReadability">0.0</span></p>
      <h4>Top 20 Words</h4>
      <ul id="topWords"></ul>
    </div>
  </div>
  
  <script src="app.js"></script>
</body>
</html>
```

**Acceptance Criteria:**
- [ ] HTML renders without errors
- [ ] Form and progress sections switch visibility correctly

---

### T4.2 — Real-Time Progress Display (7 min)

**Objective:** Connect WebSocket, render live updates.

**Deliverables:**

**app.js:**
```javascript
let ws = null;
let jobId = null;

async function submitJob() {
  const urls = document.getElementById('urlInput').value
                        .split('\n')
                        .filter(u => u.trim());
  
  if (!urls.length) {
    alert('Enter at least one URL');
    return;
  }
  
  const res = await fetch('/api/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ urls })
  });
  
  const data = await res.json();
  jobId = data.jobId;
  
  // Show progress section
  document.getElementById('form-section').style.display = 'none';
  document.getElementById('progress-section').style.display = 'block';
  document.getElementById('jobId').textContent = jobId;
  
  // Initialize rows
  urls.forEach((url, idx) => {
    const tbody = document.getElementById('itemsTable');
    const tr = document.createElement('tr');
    tr.id = `item-${idx}`;
    tr.innerHTML = `
      <td>${url}</td>
      <td id="stage-${idx}">QUEUED</td>
      <td id="state-${idx}">PENDING</td>
      <td id="error-${idx}"></td>
    `;
    tbody.appendChild(tr);
  });
  
  // Connect WebSocket
  connectWebSocket();
}

function connectWebSocket() {
  ws = new WebSocket(`ws://localhost:8080/api/jobs/${jobId}/stream`);
  
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    
    if (msg.eventType === 'item_update') {
      const item = msg.itemStatus;
      document.getElementById(`stage-${item.index}`).textContent = item.stage;
      document.getElementById(`state-${item.index}`).textContent = item.state;
      if (item.error) {
        document.getElementById(`error-${item.index}`).textContent = item.error;
      }
    } else if (msg.eventType === 'aggregate_update') {
      const agg = msg.aggregates;
      document.getElementById('docsProcessed').textContent = agg.documentsProcessed;
      document.getElementById('avgReadability').textContent = agg.averageReadability.toFixed(2);
      
      const wordsList = document.getElementById('topWords');
      wordsList.innerHTML = '';
      Object.entries(agg.topWords).slice(0, 20).forEach(([word, count]) => {
        const li = document.createElement('li');
        li.textContent = `${word} (${count})`;
        wordsList.appendChild(li);
      });
    }
  };
  
  ws.onerror = () => alert('WebSocket error');
  ws.onclose = () => alert('WebSocket closed');
}
```

**Acceptance Criteria:**
- [ ] WebSocket connects on job submission
- [ ] Item rows update in real-time
- [ ] Aggregates update without page reload
- [ ] Top words displayed correctly

---

### T4.3 — Past Jobs View (3 min)

**Objective:** Display history of completed jobs (optional for 90 min).

**Deliverables:**

```html
<div id="history-section">
  <h2>Past Jobs</h2>
  <table id="jobsTable">
    <thead>
      <tr>
        <th>Job ID</th>
        <th>Status</th>
        <th>Total</th>
        <th>Complete</th>
        <th>Errors</th>
        <th>Avg Readability</th>
        <th>Duration (sec)</th>
      </tr>
    </thead>
    <tbody></tbody>
  </table>
</div>

<script>
async function loadJobHistory() {
  const res = await fetch('/api/jobs');
  const jobs = await res.json();
  
  const tbody = document.querySelector('#jobsTable tbody');
  jobs.forEach(job => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${job.jobId}</td>
      <td>${job.state}</td>
      <td>${job.totalItems}</td>
      <td>${job.itemsComplete}</td>
      <td>${job.itemsErrored}</td>
      <td>${job.avgReadability.toFixed(2)}</td>
      <td>${(job.duration / 1000).toFixed(1)}</td>
    `;
    tbody.appendChild(tr);
  });
}

// Load on page init
window.addEventListener('load', loadJobHistory);
</script>
```

**Acceptance Criteria:**
- [ ] GET /api/jobs fetches and displays job history

---

## PHASE 5: Testing & Hardening (5 minutes)

Verify pipeline correctness and security.

### T5.1 — End-to-End Test (3 min)

**Objective:** Run with 15 sample URLs, verify all stages process.

**Test Script:**
```java
@SpringBootTest
class PipelineE2ETest {
  
  @Test
  void testFullPipeline() throws Exception {
    List<String> urls = List.of(
      "https://en.wikipedia.org/wiki/Concurrency_(computer_science)",
      "https://en.wikipedia.org/wiki/Thread_pool",
      // ... 13 more
    );
    
    // Submit job
    String jobId = submitJob(urls);
    
    // Poll for completion
    for (int i = 0; i < 60; i++) {  // 60 sec timeout
      JobStatus status = getJob(jobId);
      if (status.state == JobState.COMPLETED) {
        break;
      }
      Thread.sleep(1000);
    }
    
    // Verify results
    JobStatus final = getJob(jobId);
    assertThat(final.aggregates.documentsProcessed).isGreaterThan(0);
    assertThat(final.aggregates.averageReadability).isGreaterThan(0);
    assertThat(final.aggregates.topWords).isNotEmpty();
  }
}
```

**Acceptance Criteria:**
- [ ] All 15 URLs processed
- [ ] No exceptions during pipeline
- [ ] Aggregates computed correctly
- [ ] All items reach DONE or ERROR state

---

### T5.2 — Security Spot-Checks (2 min)

**Checklist:**

- [ ] **Invalid URL:** POST with `{"urls": ["file:///etc/passwd"]}` → rejected
- [ ] **Oversized content:** Request URL with 10MB response → rejected with CONTENT_SIZE_EXCEEDED
- [ ] **Malformed URL:** `{"urls": ["not a url"]}` → marked as INVALID_URL
- [ ] **SQL injection:** Job ID contains quotes → DB query still safe (prepared statements)
- [ ] **Large word frequencies:** Document with 1M unique words → capped at 100k
- [ ] **Concurrent writes:** Submit 5 jobs simultaneously → no data corruption

**Manual Tests:**
```bash
# Test 1: Invalid URL scheme
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"urls": ["file:///etc/passwd"]}'
# Expected: item marked as INVALID_URL error

# Test 2: Concurrent jobs
for i in {1..5}; do
  curl -X POST http://localhost:8080/api/jobs \
    -H "Content-Type: application/json" \
    -d '{"urls": ["https://example.com"]}' &
done
# Expected: all jobs process without corruption
```

**Acceptance Criteria:**
- [ ] All security checks pass without data corruption

---

## Summary Table

| Phase | Tasks | Time | Cumulative |
|-------|-------|------|-----------|
| **1. Foundation** | T1.1–T1.3 | 20 min | 20 min |
| **2. Pipeline** | T2.1–T2.4 | 30 min | 50 min |
| **3. API** | T3.1–T3.4 | 20 min | 70 min |
| **4. Frontend** | T4.1–T4.3 | 15 min | 85 min |
| **5. Testing** | T5.1–T5.2 | 5 min | 90 min |

---

## Bonus Tasks (if time allows, after 90 min)

- **T6.1** — Configurable concurrency: Add UI/API endpoints to change thread pools dynamically
- **T6.2** — Job cancellation: Interrupt executors, mark items as CANCELLED
- **T6.3** — Retry logic: 2x retry on FETCH_TIMEOUT before marking FAILED
- **T6.4** — Dockerize: Write Dockerfile, docker-compose.yml

---

**Next Step:** Proceed to Phase 1 (T1.1 — Spring Boot Setup) in 20 minutes.

