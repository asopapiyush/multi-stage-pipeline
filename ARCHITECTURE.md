# Multi-Stage Parallel Document Processing Pipeline — Architecture Document

**Project:** Document Processing Pipeline  
**Duration:** 90 minutes  
**Date:** 2026-07-16  
**Tech Stack:** Java/Spring Boot  

---

## Executive Summary

This document defines the architecture for a real-time concurrent document processing pipeline with three distinct stages, each handling different workload characteristics (I/O-bound fetch, CPU-bound analysis, sequential storage). The design prioritizes:

1. **Thread safety** at the storage layer
2. **Backpressure** between stages to prevent memory leaks
3. **Real-time observability** via WebSocket/SSE streams
4. **Security** at all boundaries (input validation, output sanitization)
5. **Graceful degradation** (failed fetches don't block pipeline)

---

## 1. System Architecture Overview

### 1.1 High-Level Flow

```
User Input (URLs)
    ↓
[Stage 1: Fetch]  (10 concurrent, I/O-bound)
    ↓ (Queue: max 5 items)
[Stage 2: Analyze] (3 concurrent, CPU-bound)
    ↓ (Direct handoff, no queue)
[Stage 3: Store]   (1 serial, thread-safe)
    ↓
Output (SSE/WS stream + Persistence)
```

### 1.2 Design Principles

| Principle | Rationale |
|-----------|-----------|
| **Bounded Queues** | Prevent unbounded memory growth on large batches |
| **Backpressure** | Stage 1 pauses when Stage 2's queue fills (5 items max) |
| **Thread Confinement** | Stage 3 is sequential; no concurrent writes to storage |
| **Producer-Consumer Pattern** | Proper handoff between stages; no polling |
| **Fail-Soft** | Bad fetch → item marked error, pipeline continues |

---

## 2. Detailed Architecture

### 2.1 Stage 1: Fetch (I/O-Bound)

**Concurrency:** 10 concurrent requests  
**Queue Management:** Internal queue or thread pool  
**Backpressure:** Pauses when Stage 2's input queue reaches 5 items  

**Responsibilities:**
- Validate URL format (security: URL scheme, domain whitelist option)
- Fetch HTTP content with timeout (5s recommended)
- Extract plain text (or HTML title + body for simple case)
- Mark failed fetches as errors (non-blocking)
- Signal completion or error to Stage 2

**Implementation Details:**
```
ThreadPoolExecutor(
  corePoolSize: 10,
  maxPoolSize: 10,
  queue: LinkedBlockingQueue (unbounded, backpressure handled externally)
)

Input: URL item
├─ Validate URL (security check)
├─ HTTP GET with timeout
├─ Extract text
└─ Pass to Stage 2 queue OR mark error

Backpressure:
├─ Before submitting to ThreadPool
├─ Check Stage 2's input queue size
├─ If size >= 5: wait (BlockingQueue.take() or semaphore)
└─ Resume when Stage 2 consumes items
```

**Security Considerations:**
- URL validation: scheme (http/https only), domain filtering (optional whitelist)
- HTTP timeout: prevent hanging connections
- Content size limit: reject documents > 5MB (prevent OOM)
- User-Agent header: identify as bot to respect robots.txt

---

### 2.2 Stage 2: Analyze (CPU-Bound)

**Concurrency:** 3 concurrent workers  
**Queue:** Bounded input queue (max 5 items)  
**Input Source:** Stage 1's output queue  

**Responsibilities:**
- Extract all hyperlinks (href attributes)
- Count word frequencies (case-insensitive)
- Compute readability score: `(avg_sentence_length * avg_word_length)`
- Pass results to Stage 3

**Implementation Details:**
```
ThreadPoolExecutor(
  corePoolSize: 3,
  maxPoolSize: 3,
  queue: LinkedBlockingQueue(5)  ← Bounded!
)

Input: FetchedDocument
├─ Extract links (regex or HTML parser)
├─ Tokenize text, build word frequency map
├─ Calculate readability metrics
│  ├─ sentence_count = split on [.!?]
│  ├─ word_count = count tokens
│  ├─ char_count = sum char lengths
│  └─ score = (word_count/sentence_count) * (char_count/word_count)
└─ Queue to Stage 3

Backpressure:
├─ Stage 1 monitors Stage 2's queue
├─ If queue.size() >= 5: Stage 1 yields
└─ Stage 1 resumes when Stage 2 drains items
```

**Word Frequency Algorithm (simple):**
```
words = text.toLowerCase()
        .split("\\s+")
        .filter(w -> w.matches("[a-z]+"))  // only alphabetic
        .collect(groupingBy(identity(), counting()))
```

**Security Considerations:**
- Bound computation time per document (watch for degenerate inputs)
- Regex timeouts: use TimeoutTask or compile with Pattern.DOTALL
- Memory limits: ensure word map doesn't explode (cap at 100k unique words per doc)

---

### 2.3 Stage 3: Store & Aggregate (Sequential)

**Concurrency:** Serial execution (one item at a time)  
**Thread Safety:** No concurrent writes to persistent state  
**Aggregation:** Running totals (docs_processed, avg_readability, top_20_words)  

**Responsibilities:**
- Write document result to persistent storage (SQLite/JSON)
- Update running aggregates (thread-safe)
- Maintain top 20 words globally across batch
- Signal completion to WebSocket/SSE stream

**Implementation Details:**
```
SingleThreadExecutor (or synchronized methods)

Input: AnalyzedDocument
├─ Write to DB/JSON
├─ Update aggregates (synchronized):
│  ├─ docs_processed++
│  ├─ avg_readability = (sum + new_score) / count
│  ├─ Merge word frequencies into global map
│  └─ Keep top 20 by frequency
└─ Emit progress event (WebSocket)

Thread Safety:
├─ Use ReentrantReadWriteLock for aggregate reads (frontend polls)
├─ Synchronized writes to DB
└─ Or: volatile fields + ConcurrentHashMap for frequency map
```

**Aggregate Data Structure:**
```java
@Data
class JobAggregate {
  int documentsProcessed;
  double averageReadability;
  Map<String, Long> topWords;  // top 20, sorted by frequency
  long startTime;
  long lastUpdated;
}
```

**Security Considerations:**
- SQL injection: use prepared statements (SQLite with parameterized queries)
- Large aggregates: cap top_words at 20 entries (memory bounded)
- Concurrent reads: use read lock when frontend requests aggregate

---

## 3. Data Flow & Handoff Protocol

### 3.1 Stage 1 → Stage 2 (Backpressure)

```
Stage 1 fetches URL:
1. HTTP GET → extract text → wrap in FetchResult
2. Check: is Stage 2's queue size >= 5?
   YES → wait (BlockingQueue.offer with timeout, or Semaphore)
   NO → offer to Stage 2's queue
3. On Stage 2 consuming: Stage 1 resumes

Implementation:
├─ Use LinkedBlockingQueue for Stage 2 input
├─ Stage 1: queue.offer(result, 10, SECONDS)
│            If timeout: retry or mark item failed
└─ This implements backpressure automatically
```

### 3.2 Stage 2 → Stage 3 (Direct Handoff)

```
Stage 2 analyzes document:
1. Compute metrics → wrap in AnalysisResult
2. Submit to Stage 3's executor (unbounded internally, but single-threaded)
3. No queue between them; Stage 2 immediately releases the thread

Implementation:
├─ Stage 3: SingleThreadExecutor or synchronized queue
├─ Stage 2: futures.add(stage3Executor.submit(result))
└─ No backpressure; Stage 2 accepts all Stage 3 submissions
```

### 3.3 Stage 3 → Frontend (SSE/WebSocket)

```
Stage 3 completes item:
1. Write to storage
2. Update aggregate
3. Emit event to all WebSocket clients

Event structure:
{
  "jobId": "abc-123",
  "event": "item_complete",
  "itemIndex": 3,
  "stage": "stored",
  "timestamp": 1626...000,
  "aggregates": {
    "documentsProcessed": 4,
    "averageReadability": 28.5,
    "topWords": [{"word": "concurrency", "count": 45}, ...]
  }
}
```

---

## 4. Security Architecture

### 4.1 Input Validation Layer (Stage 1)

| Check | Threat | Implementation |
|-------|--------|-----------------|
| URL format | Malformed URLs, injection | Regex or URLValidator (java.net.URL) |
| URL scheme | file://, gopher://, SSRF | Whitelist: http, https only |
| Domain whitelist | SSRF to internal networks | Optional: config list of allowed domains |
| Content size | OOM, DoS | Reject if `Content-Length > 5MB` |
| HTTP timeout | Slowloris, hanging | 5s socket + 10s total timeout |

**Code Pattern:**
```java
if (!url.startsWith("http://") && !url.startsWith("https://")) {
  throw new SecurityException("Only HTTP(S) allowed");
}
// Whitelist check
if (disallowedDomains.contains(extractDomain(url))) {
  throw new SecurityException("Domain not allowed");
}
```

### 4.2 Processing Layer (Stage 2)

| Check | Threat | Implementation |
|-------|--------|-----------------|
| Regex timeout | ReDoS (Regular Expression Denial of Service) | Use Pattern with timeout or bounded quantifiers |
| Word map size | Memory exhaustion | Cap unique words at 100k per doc |
| Sentence parsing | Malformed text | Graceful split, handle edge cases |

### 4.3 Storage Layer (Stage 3)

| Check | Threat | Implementation |
|-------|--------|-----------------|
| SQL injection | Data corruption, breach | Prepared statements only (SQLite parameterized) |
| Path traversal | Unauthorized file access | If using JSON files, sanitize job ID (alphanumeric + dash) |
| Concurrent writes | Race conditions | Single-threaded executor or write lock |
| Aggregate corruption | Inconsistent reads | Read lock for frontend polls |

**Code Pattern:**
```java
// SAFE: Prepared statement
PreparedStatement ps = conn.prepareStatement("INSERT INTO results (job_id, url, score) VALUES (?, ?, ?)");
ps.setString(1, jobId);
ps.setString(2, url);
ps.setDouble(3, score);

// UNSAFE: String concatenation
Statement stmt = conn.createStatement();
stmt.execute("INSERT INTO results VALUES ('" + jobId + "', ...)");  // ← NO!
```

### 4.4 Frontend/API Layer

| Check | Threat | Implementation |
|-------|--------|-----------------|
| Job ID validation | Unauthorized data access | UUID or alphanumeric, check ownership if multi-user |
| Rate limiting | DoS via API | Optional: limit /jobs POST to 10/min per IP |
| CORS | CSRF, unauthorized cross-origin access | Configure Spring CORS (allow specific origins if SPA) |
| Content-Security-Policy | XSS | Set CSP headers on all responses |

---

## 5. Implementation Task Breakdown

### Phase 1: Foundation (20 min)

**Tasks:**
1. **T1.1** — Spring Boot project setup (Maven, dependencies)
   - Dependencies: spring-boot-starter-web, spring-boot-starter-webflux (for async), jsoup (HTML parsing)
   - Build: JAR with embedded Tomcat
   
2. **T1.2** — Data models
   - `JobRequest` (List<String> urls)
   - `FetchResult` (URL, content, status, error)
   - `AnalysisResult` (links, wordFreq, readabilityScore)
   - `JobAggregate` (processedCount, avgScore, topWords)
   - `JobStatus` (job ID, state, items, lastUpdated)

3. **T1.3** — Persistence layer
   - SQLite schema: tables for jobs, job_items, aggregates
   - OR: JSON file-based (maps job ID → results JSON)
   - DAO/Repository layer with thread-safe access

### Phase 2: Pipeline Architecture (30 min)

**Tasks:**
4. **T2.1** — Stage 1 executor setup
   - ThreadPoolExecutor(10, 10) with I/O timeout
   - URL validation, HTTP fetch with timeout
   - Queue to Stage 2 with backpressure logic

5. **T2.2** — Stage 2 executor setup
   - ThreadPoolExecutor(3, 3) with LinkedBlockingQueue(5)
   - HTML parsing (jsoup) + text extraction
   - Link extraction (regex or DOM traversal)
   - Word frequency + readability computation

6. **T2.3** — Stage 3 executor setup
   - SingleThreadExecutor for sequential storage
   - Database writes with prepared statements
   - Aggregate updates (synchronized or lock-based)

7. **T2.4** — Backpressure integration
   - Monitor Stage 2 queue before Stage 1 submit
   - Use Semaphore or BlockingQueue.offer() with timeout
   - Logging for queue depth debugging

### Phase 3: REST API & Streaming (20 min)

**Tasks:**
8. **T3.1** — POST /jobs endpoint
   - Accept URL list, validate input
   - Create job record, generate UUID
   - Start pipeline asynchronously
   - Return job ID immediately

9. **T3.2** — GET /jobs/:id endpoint
   - Fetch current job state from DB
   - Return item-by-item status + aggregates
   - Handle not-found gracefully

10. **T3.3** — WebSocket/SSE on /jobs/:id/stream
    - Broadcast item completions to all connected clients
    - Stream aggregate updates
    - Handle client disconnection

11. **T3.4** — GET /jobs endpoint
    - List all past jobs with summary stats
    - Pagination (optional)

### Phase 4: Frontend (15 min)

**Tasks:**
12. **T4.1** — HTML + Form UI
    - Textarea for URL input (one per line)
    - Submit button → POST /jobs
    - Display job ID after submission

13. **T4.2** — Real-time progress display
    - Connect WebSocket/SSE to /stream
    - Render table: URL | Stage | Status | Error
    - Queue depth indicators
    - Live aggregate stats (docs_count, avg_score, top_words)

14. **T4.3** — Past jobs view
    - Display job history from GET /jobs
    - Click to expand job details

### Phase 5: Testing & Hardening (5 min)

**Tasks:**
15. **T5.1** — End-to-end test
    - Submit 15 test URLs
    - Verify all stages process
    - Check aggregates for correctness

16. **T5.2** — Security spot-checks
    - Malformed URL submission
    - SQL injection attempt (if JSON, path traversal)
    - Large batch stress test

---

## 6. Technology Choices & Justifications

| Component | Choice | Why |
|-----------|--------|-----|
| **Web Framework** | Spring Boot | Industry standard, battle-tested concurrency, built-in WebSocket support |
| **HTTP Client** | Spring RestTemplate or OkHttp | Timeouts, connection pooling, interceptors |
| **HTML Parsing** | jsoup | Safe, no regex ReDoS, handles malformed HTML |
| **Persistence** | SQLite (file-based) | No server, portable, ACID compliance, prepared statements |
| **Async/Streams** | Spring Flux (optional) or Threads | Thread pools sufficient for 90min scope; Flux adds complexity |
| **Concurrency Primitives** | ThreadPoolExecutor + BlockingQueue | JDK standard, no external deps, backpressure built-in |
| **Real-time Updates** | WebSocket (Spring WebSocket) | Lower latency than SSE, bidirectional if needed later |

---

## 7. Security Checklist

- [ ] URL validation (scheme, domain, length)
- [ ] HTTP timeout (5s)
- [ ] Content size limit (5MB)
- [ ] HTML parsing library (jsoup, not regex)
- [ ] Regex timeouts (or avoid complex regex)
- [ ] SQL: prepared statements only
- [ ] Job ID: UUID or alphanumeric (no path traversal)
- [ ] Aggregate bounds (top 20 words, word map cap 100k)
- [ ] CORS configured (if SPA)
- [ ] CSP headers on HTML
- [ ] No debug logging of sensitive data (URLs, auth headers)
- [ ] Thread pool reject policy configured (CallerRunsPolicy or abort + retry)

---

## 8. Deployment & Ops

### Docker (Bonus)

```dockerfile
FROM openjdk:17-slim
COPY target/pipeline.jar /app.jar
EXPOSE 8080
CMD ["java", "-jar", "/app.jar"]
```

### Environment Variables

```
PIPELINE_STAGE1_THREADS=10
PIPELINE_STAGE2_THREADS=3
PIPELINE_STAGE2_QUEUE_SIZE=5
HTTP_TIMEOUT_SEC=5
CONTENT_SIZE_LIMIT_MB=5
DB_PATH=/data/pipeline.db
```

### Monitoring

- Log queue depths periodically (DEBUG level)
- Track stage latencies (Micrometer/Actuator, optional for 90min)
- Alert if queue full (backpressure stuck)

---

## 9. Decisions for the 90-Minute Scope

| Feature | Include? | Rationale |
|---------|----------|-----------|
| Graceful shutdown | Yes (Stage 1 pauses) | Core requirement |
| Retry logic | Optional | Time-constrained; fail-soft is sufficient |
| Job cancellation | Bonus | If time permits (interrupt executors) |
| Configurable concurrency | Bonus | UI/API toggles, nice but not critical |
| Dockerize | Bonus | Final 5 min |
| Metrics/Actuator | No | Out of scope; logs sufficient |
| Horizontal scaling | No | Single-process design |
| Authentication | No | Assumes trusted environment |

---

## 10. Known Limitations & Future Work

1. **No persistence across restarts** — Job state lost on server shutdown (add DB checkpoint)
2. **Single-process** — No horizontal scaling (could add message queue later)
3. **Simple readability score** — Not validated against real metrics; swap in Flesch-Kincaid if needed
4. **No rate limiting** — Could add token bucket per IP
5. **No audit logging** — Add for compliance-heavy deployments
6. **No graceful shutdown signal** — Add SIGTERM handler to drain queues

---

## Summary

This architecture balances **simplicity** (90-min timeline) with **correctness** (backpressure, thread safety, security). The three-stage pipeline with bounded queues and sequential storage prevents memory leaks and race conditions. Security is enforced at each layer: input validation (Stage 1), resource limits (Stage 2), prepared statements (Stage 3). Follow the task breakdown sequentially; each phase builds on the last.

**Key Design Principle:** *Bounded resources, explicit handoffs, single point of truth for shared state (Stage 3).*

