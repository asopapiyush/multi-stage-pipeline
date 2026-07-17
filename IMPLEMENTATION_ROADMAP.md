# Implementation Roadmap & Quick Reference

**Project:** Multi-Stage Parallel Document Processing Pipeline  
**Duration:** 90 minutes  
**Status:** Ready to implement  

---

## Quick Navigation

| Document | Purpose |
|----------|---------|
| **ARCHITECTURE.md** | System design, stage details, security layer-by-layer |
| **TASKS.md** | Detailed 16-task breakdown across 5 phases with acceptance criteria |
| **SECURITY_AND_CODE_REVIEW.md** | Security risks, mitigations, and code review checklist |
| **DESIGN_GUIDELINES.md** | Design patterns, data flow diagrams, operational patterns |
| **IMPLEMENTATION_ROADMAP.md** | This file — execution flow and quick reference |

---

## Phase Breakdown (90 Minutes)

### Phase 1: Foundation (20 min) — **T1.1 to T1.3**

**Goal:** Project skeleton + data models + persistence

| Task | Time | What | Deliverable |
|------|------|------|-------------|
| **T1.1** | 5m | Spring Boot setup | `pom.xml` with dependencies; app runs on :8080 |
| **T1.2** | 8m | Data models | 7 classes (JobRequest, FetchResult, AnalysisResult, JobAggregate, JobStatus, ItemStatus, StreamMessage) |
| **T1.3** | 7m | SQLite persistence | Schema (jobs, job_items, job_results, job_aggregates); JobRepository DAO |

**Checkpoint:** Can compile and create a job record in DB ✓

---

### Phase 2: Pipeline Architecture (30 min) — **T2.1 to T2.4**

**Goal:** Three concurrent stages with backpressure

| Task | Time | What | Deliverable |
|------|------|------|-------------|
| **T2.1** | 10m | Stage 1 (Fetch) | 10-thread executor; URL validation; HTTP fetch with timeout; backpressure check |
| **T2.2** | 10m | Stage 2 (Analyze) | 3-thread executor; LinkedBlockingQueue(5); HTML parsing; word frequency; readability score |
| **T2.3** | 7m | Stage 3 (Store) | Single-thread executor; DB writes; aggregate updates under lock; top-20 words |
| **T2.4** | 3m | Integration | Wire 3 stages; queue handoffs; logging queue depth |

**Checkpoint:** Can process 3 URLs through full pipeline ✓

**Key Code Patterns:**

```java
// Stage 1 backpressure
while (stage2Queue.size() >= 5) {
  Thread.sleep(100);
}
stage2Queue.offer(result);

// Stage 2 bounded input
BlockingQueue<FetchResult> inputQueue = new LinkedBlockingQueue<>(5);
FetchResult result = inputQueue.take();  // Blocks if empty

// Stage 3 aggregate safety
aggregateLock.writeLock().lock();
try {
  aggregate.documentsProcessed++;
  aggregate.averageReadability = ...;
} finally {
  aggregateLock.writeLock().unlock();
}
```

---

### Phase 3: REST API & Streaming (20 min) — **T3.1 to T3.4**

**Goal:** HTTP endpoints + WebSocket real-time updates

| Task | Time | What | Deliverable |
|------|------|------|-------------|
| **T3.1** | 5m | POST /jobs | Accept URLs, create job, start pipeline async, return jobId |
| **T3.2** | 4m | GET /jobs/:id | Return JobStatus (items + aggregates) |
| **T3.3** | 8m | WS /jobs/:id/stream | Broadcast item_update + aggregate_update events |
| **T3.4** | 3m | GET /jobs | List past jobs with summary stats |

**Checkpoint:** Can submit job and query status via API ✓

**Sample Curl Requests:**

```bash
# Submit job
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://example.com", "https://wikipedia.org/wiki/Concurrency"]}'
# → {"jobId": "abc-123"}

# Query job status
curl http://localhost:8080/api/jobs/abc-123
# → { "jobId": "abc-123", "state": "RUNNING", "items": [...], "aggregates": {...} }

# List all jobs
curl http://localhost:8080/api/jobs
# → [ {...}, {...}, ... ]

# Subscribe to WebSocket (from browser console)
ws = new WebSocket("ws://localhost:8080/api/jobs/abc-123/stream");
ws.onmessage = (e) => console.log(JSON.parse(e.data));
```

---

### Phase 4: Frontend (15 min) — **T4.1 to T4.3**

**Goal:** HTML + real-time progress UI

| Task | Time | What | Deliverable |
|------|------|------|-------------|
| **T4.1** | 5m | HTML form & layout | Input textarea + progress table + aggregates display |
| **T4.2** | 7m | Real-time progress | WebSocket connection; live updates to table + aggregates |
| **T4.3** | 3m | Job history | Display past jobs from GET /jobs |

**Checkpoint:** Can submit URLs via UI and see progress live ✓

**Frontend User Flow:**

```
1. User opens http://localhost:8080
2. Form visible: textarea for URLs
3. User pastes 15 Wikipedia URLs, clicks Submit
4. POST /jobs sent, jobId returned
5. Form disappears, progress section appears
6. WebSocket connects to /stream
7. Table shows each URL + stage (QUEUED, FETCHING, ANALYZING, STORING, DONE)
8. Live aggregate stats update (count, average, top words)
9. Job completes → show final results
10. "Back to Form" button to submit new batch
11. "History" tab shows past jobs
```

---

### Phase 5: Testing & Hardening (5 min) — **T5.1 to T5.2**

**Goal:** Verify pipeline correctness and security

| Task | Time | What | Deliverable |
|------|------|------|-------------|
| **T5.1** | 3m | End-to-end test | Process 15 Wikipedia URLs; verify all stages complete; check aggregates |
| **T5.2** | 2m | Security checks | Test: SSRF rejection, SQL injection safe, oversized content rejected |

**Checkpoint:** All 15 URLs process without errors; no data corruption ✓

**Manual Test Scenarios:**

```bash
# Test 1: Happy path
URLs: [15 Wikipedia articles]
Expected: All succeed, aggregates computed, top 20 words shown

# Test 2: Mixed success/failure
URLs: [10 valid + "file:///etc/passwd" + oversized URL]
Expected: 10 succeed, 2 marked as FAILED, aggregates from 10

# Test 3: Backpressure check
Monitor logs while processing
Expected: "Stage 2 queue size: 5" appears frequently (shows backpressure active)

# Test 4: Concurrent jobs
Submit 3 jobs simultaneously
Expected: All 3 process independently, no data corruption
```

---

## Critical Implementation Details

### 1. Backpressure Logic (Highest Priority)

This is the trickiest part — get it right:

```java
// Stage 1: BEFORE submitting to thread pool
@Override
public void fetchUrl(String url, BlockingQueue<FetchResult> stage2Queue) {
  try {
    // ← INSERT BACKPRESSURE CHECK HERE
    while (stage2Queue.size() >= MAX_QUEUE_SIZE) {
      log.debug("Stage 2 queue full, waiting...");
      Thread.sleep(100);  // Simple, effective backpressure
    }
    
    String content = fetchWithTimeout(url, Duration.ofSeconds(5));
    FetchResult result = new FetchResult(url, content, SUCCESS, null);
    
    // ← NOW offer to queue (size < MAX by the time we get here)
    if (!stage2Queue.offer(result, 10, TimeUnit.SECONDS)) {
      log.warn("Failed to queue result for {}", url);
      // Retry or mark as failed
    }
  } catch (SecurityException | TimeoutException e) {
    offerError(stage2Queue, url, ...);
  }
}
```

**Why Not Use Semaphore?** Either approach works; sleep loop is simpler for 90-min scope.

---

### 2. Thread-Safe Aggregate Updates (Second Priority)

```java
// WRONG: Race condition
aggregate.documentsProcessed++;
aggregate.averageReadability = (oldSum + newScore) / count;

// RIGHT: Atomic under lock
aggregateLock.writeLock().lock();
try {
  aggregate.documentsProcessed++;
  double sum = aggregate.averageReadability * (aggregate.documentsProcessed - 1);
  aggregate.averageReadability = (sum + result.readabilityScore) / aggregate.documentsProcessed;
  aggregate.lastUpdated = System.currentTimeMillis();
} finally {
  aggregateLock.writeLock().unlock();
}
```

---

### 3. Security Input Validation (Third Priority)

```java
// In T1.1 or T2.1 (URL validation)
private void validateUrl(String url) throws SecurityException {
  // 1. Null/empty
  if (url == null || url.isEmpty()) throw new SecurityException("Empty URL");
  
  // 2. Length
  if (url.length() > 2048) throw new SecurityException("URL too long");
  
  // 3. Scheme
  if (!url.startsWith("http://") && !url.startsWith("https://")) {
    throw new SecurityException("Only HTTP(S) allowed");
  }
  
  // 4. SSRF: reject private IPs
  try {
    URL parsed = new URL(url);
    InetAddress addr = InetAddress.getByName(parsed.getHost());
    if (addr.isLoopbackAddress() || addr.isPrivateAddress()) {
      throw new SecurityException("Private IP not allowed");
    }
  } catch (UnknownHostException e) {
    throw new SecurityException("Cannot resolve hostname: " + url);
  }
}
```

---

### 4. HTML Parsing (Safe, No ReDoS)

```java
// Use jsoup (safe, no regex ReDoS)
Document doc = Jsoup.parse(htmlContent, "", Parser.htmlParser());

// Extract links
List<String> links = doc.select("a[href]")
                        .stream()
                        .map(el -> el.attr("href"))
                        .filter(href -> !href.isEmpty())
                        .collect(Collectors.toList());

// Extract plain text
String plainText = doc.body().text();
```

---

### 5. Database Prepared Statements (Non-Negotiable)

```java
// SAFE
String sql = "INSERT INTO job_results (job_id, url, readability_score) VALUES (?, ?, ?)";
PreparedStatement ps = conn.prepareStatement(sql);
ps.setString(1, jobId);
ps.setString(2, url);
ps.setDouble(3, score);
ps.executeUpdate();

// UNSAFE (never do this)
String unsafe = "INSERT INTO job_results VALUES ('" + jobId + "', ...)";
```

---

## File Structure (Minimal)

```
interview/
├─ src/main/java/com/pipeline/
│  ├─ controller/
│  │  └─ JobController.java
│  ├─ service/
│  │  ├─ JobService.java
│  │  └─ PipelineOrchestrator.java
│  ├─ stage/
│  │  ├─ FetchStage.java
│  │  ├─ AnalyzeStage.java
│  │  └─ StoreStage.java
│  ├─ model/
│  │  ├─ JobRequest.java
│  │  ├─ FetchResult.java
│  │  ├─ AnalysisResult.java
│  │  ├─ JobAggregate.java
│  │  └─ JobStatus.java
│  ├─ repository/
│  │  └─ JobRepository.java
│  └─ PipelineApplication.java
├─ src/main/resources/
│  ├─ application.properties
│  ├─ schema.sql (SQLite DDL)
│  └─ static/
│     ├─ index.html
│     └─ app.js
├─ pom.xml
└─ README.md
```

---

## Time Allocation Per Task

| Phase | Task | Estimate | Actual | Notes |
|-------|------|----------|--------|-------|
| 1 | T1.1 Setup | 5m | — | Use Spring Initializr to save time |
| 1 | T1.2 Models | 8m | — | Use Lombok @Data to reduce boilerplate |
| 1 | T1.3 Persistence | 7m | — | Copy/paste SQLite schema |
| — | **Subtotal** | **20m** | — | — |
| 2 | T2.1 Stage 1 | 10m | — | Core: backpressure + timeout |
| 2 | T2.2 Stage 2 | 10m | — | Core: bounded queue + jsoup |
| 2 | T2.3 Stage 3 | 7m | — | Core: single thread + lock |
| 2 | T2.4 Integration | 3m | — | Wire queues, test locally |
| — | **Subtotal** | **30m** | — | — |
| 3 | T3.1 POST /jobs | 5m | — | Straightforward; input validation |
| 3 | T3.2 GET /jobs/:id | 4m | — | Query DB, return status |
| 3 | T3.3 WS /stream | 8m | — | Tricky; need WebSocket handler + subscription map |
| 3 | T3.4 GET /jobs | 3m | — | List query, simple |
| — | **Subtotal** | **20m** | — | — |
| 4 | T4.1 HTML | 5m | — | Basic form + table; CSS minimal |
| 4 | T4.2 WebSocket | 7m | — | Connect WS, update table, show aggregates |
| 4 | T4.3 History | 3m | — | Fetch /jobs, render table |
| — | **Subtotal** | **15m** | — | — |
| 5 | T5.1 E2E test | 3m | — | Submit URLs, poll status, verify results |
| 5 | T5.2 Security | 2m | — | Manual tests: SSRF, SQL injection, oversized |
| — | **Subtotal** | **5m** | — | — |
| — | **TOTAL** | **90m** | — | — |

---

## Git Commit Markers

Recommended commit points:

```bash
# After Phase 1
git commit -m "feat: data models and SQLite persistence"

# After Phase 2
git commit -m "feat: three-stage pipeline with backpressure"

# After Phase 3
git commit -m "feat: REST API and WebSocket streaming"

# After Phase 4
git commit -m "feat: frontend UI for real-time progress"

# After Phase 5
git commit -m "test: end-to-end pipeline verification and security hardening"
```

---

## Common Gotchas & Solutions

| Gotcha | Solution |
|--------|----------|
| **Backpressure doesn't trigger** | Check: Stage 2 queue capacity = 5; Stage 1 checks size before offering |
| **WebSocket messages not broadcast** | Verify: subscription map stores sessions by jobId; loop over all sessions when sending |
| **Aggregate numbers wrong** | Check: lock held during update; read lock held during read; copy returned (no mutation) |
| **SQL injection test fails** | Verify: PreparedStatement used everywhere; no string concatenation |
| **SSRF check not working** | Check: InetAddress.isPrivateAddress() covers all ranges; file:// scheme rejected first |
| **ReDoS timeout** | Check: text length capped at 1MB; regex patterns are simple (no quantifier nesting) |
| **Frontend updates lag** | Check: WebSocket connects; onmessage handler updates DOM; no long polling |

---

## Success Criteria

### End of 90 Minutes

- [ ] **Frontend:** Can submit 15 URLs via form
- [ ] **Pipeline:** All 3 stages process concurrently
- [ ] **Backpressure:** Stage 1 pauses when Stage 2 queue = 5 (visible in logs)
- [ ] **Real-time:** WebSocket updates progress live (no page refresh)
- [ ] **Aggregates:** Final stats show correct counts + average readability
- [ ] **Security:** URL validation rejects `file://`, oversized content, SSRF
- [ ] **No Errors:** Console has no exceptions; all URLs reach DONE or marked FAILED
- [ ] **Database:** All results persisted; can query /jobs to see history

### Optional (Time Permitting)

- [ ] Docker image builds and runs
- [ ] Retry logic on transient timeouts
- [ ] Job cancellation via API
- [ ] Configurable concurrency via UI

---

## Estimated Bottlenecks

1. **WebSocket handler complexity** (T3.3) — might take 10m instead of 8m
2. **Aggregate update logic** (T2.3) — race conditions hard to spot; add logging
3. **Frontend debugging** (T4.2) — WebSocket may not connect first try; use browser console

**Buffer:** Aim to finish by 85 min; use final 5 min for fixes/polish.

---

## Quick Reference: Key Classes & Methods

```java
// FetchStage
void start(List<String> urls, BlockingQueue<FetchResult> stage2Queue)
  // Main entry; submits fetch tasks to thread pool

// AnalyzeStage
BlockingQueue<FetchResult> getInputQueue()
  // Returns the bounded input queue for Stage 1 to use

// StoreStage
void processResult(AnalysisResult result)
  // Submits result to single-thread executor; updates aggregate + DB

// JobRepository
void createJob(String jobId, JobStatus status)
void updateAggregate(String jobId, JobAggregate agg)
void saveResult(String jobId, AnalysisResult result)
  // Thread-safe DB writes using prepared statements

// JobController
@PostMapping("/jobs")  // T3.1
@GetMapping("/{jobId}")  // T3.2
@GetMapping  // T3.4
  // HTTP endpoints; delegate to JobService

// WebSocket Handler
void broadcastItemUpdate(String jobId, ItemStatus item)  // Called by Stage 3
void broadcastAggregateUpdate(String jobId, JobAggregate agg)  // Called by Stage 3
  // Send events to all connected clients for this job
```

---

## Final Checklist Before Starting

- [ ] Read ARCHITECTURE.md completely
- [ ] Understand the three-stage pipeline + backpressure
- [ ] Know the 5 phases and 16 tasks
- [ ] Identify critical paths: backpressure, thread safety, security
- [ ] Have Spring Boot Initializr tab open
- [ ] Have Wikipedia URLs saved (for T5.1 testing)
- [ ] Create git repo and make first commit

**You're ready to go!** ✓

---

**Next Step:** Begin Phase 1, Task T1.1 (Spring Boot Setup). Estimated time to completion: 90 minutes.

