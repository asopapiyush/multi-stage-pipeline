# Architectural & Design Guidelines

**Project:** Multi-Stage Parallel Document Processing Pipeline  
**Date:** 2026-07-16  
**Scope:** Design principles, patterns, and flow diagrams  

---

## Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [Design Principles](#2-design-principles)
3. [Core Patterns & Their Application](#3-core-patterns--their-application)
4. [Data Flow Diagrams](#4-data-flow-diagrams)
5. [API Design](#5-api-design)
6. [Frontend Architecture](#6-frontend-architecture)
7. [Operational Patterns](#7-operational-patterns)

---

## 1. High-Level Architecture

### 1.1 System Context Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                         Frontend (Browser)                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ URL Form     │  │ Progress View│  │ Job History      │  │
│  │ (submit)     │  │ (live update)│  │ (past results)   │  │
│  └──────────────┘  └──────────────┘  └──────────────────┘  │
└──────────────┬──────────────────────────────────────────────┘
               │ HTTP/WebSocket
               ↓
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Backend                       │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  REST API Layer                                      │  │
│  │  - POST /jobs (submit batch)                         │  │
│  │  - GET /jobs/:id (query status)                      │  │
│  │  - WS /jobs/:id/stream (real-time progress)         │  │
│  │  - GET /jobs (history)                               │  │
│  └──────────────────────────────────────────────────────┘  │
│                         ↕ (events)                           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Pipeline Orchestrator                               │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐           │  │
│  │  │ Stage 1  │→ │ Stage 2  │→ │ Stage 3  │           │  │
│  │  │ Fetch    │  │ Analyze  │  │ Store    │           │  │
│  │  │ (10 th)  │  │ (3 th)   │  │ (1 th)   │           │  │
│  │  └──────────┘  └──────────┘  └──────────┘           │  │
│  └──────────────────────────────────────────────────────┘  │
│                         ↕ (persistence)                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Persistence Layer (SQLite)                          │  │
│  │  - jobs (job metadata)                               │  │
│  │  - job_items (per-URL status)                        │  │
│  │  - job_results (fetched content + analysis)          │  │
│  │  - job_aggregates (running totals)                   │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
               │ File I/O
               ↓
        ┌──────────────┐
        │ SQLite DB    │
        │ (pipeline.db)│
        └──────────────┘
```

### 1.2 Component Relationships

```
┌─────────────────────────────────────────────────┐
│ JobController (HTTP endpoints)                  │
│  • Validates input                              │
│  • Delegates to JobService                      │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ JobService (orchestration)                      │
│  • Manages job lifecycle                        │
│  • Starts PipelineOrchestrator async            │
│  • Tracks running jobs                          │
└────────────────┬────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────┐
│ PipelineOrchestrator (three-stage pipeline)     │
│  ├─ FetchStage (10 threads, I/O)               │
│  ├─ AnalyzeStage (3 threads, CPU)             │
│  └─ StoreStage (1 thread, sequential)         │
└────────────┬───────────────────────────────────┘
             │
             ↓
┌─────────────────────────────────────────────────┐
│ JobRepository (data access)                     │
│  • CRUD operations on jobs, items, results      │
│  • Thread-safe writes (prepared statements)     │
│  • Read-optimized queries                       │
└─────────────────────────────────────────────────┘
```

---

## 2. Design Principles

### 2.1 Bounded Resources

**Principle:** Every queue, pool, and map has explicit bounds to prevent unbounded growth and memory leaks.

**Application:**
| Component | Bound | Why |
|-----------|-------|-----|
| Stage 1 thread pool | 10 threads | I/O-bound; 10 concurrent HTTP requests |
| Stage 2 input queue | 5 items | Backpressure trigger; prevents CPU starvation |
| Stage 2 thread pool | 3 threads | CPU-bound; system-dependent (e.g., 4-core machine) |
| Text length | 1 MB | Prevent ReDoS and parsing overhead |
| Word frequency map | 100k entries | Prevent HashMap memory explosion |
| Content size | 5 MB | HTTP response limit |
| URLs per batch | 50 | API DoS prevention |

**Implementation Pattern:**
```java
// Anti-pattern: unbounded
List<Item> items = new ArrayList<>();  // Grows forever

// Pattern: explicit bound
BlockingQueue<Item> queue = new LinkedBlockingQueue<>(5);
Map<String, Long> freqs = new HashMap<>();
if (freqs.size() < 100_000) {
  freqs.put(word, count);
}
```

---

### 2.2 Separation of Concerns

**Principle:** Each layer has a single responsibility; data flows in one direction.

**Layers:**
1. **API Layer** (JobController)
   - Responsibility: HTTP request validation, response formatting
   - Boundary: Public HTTP surface
   
2. **Business Logic** (JobService, PipelineOrchestrator)
   - Responsibility: Job orchestration, stage coordination
   - Boundary: Internal interfaces (no HTTP)
   
3. **Execution Layer** (FetchStage, AnalyzeStage, StoreStage)
   - Responsibility: Stage-specific work (fetch, analyze, store)
   - Boundary: Internal queues and executors
   
4. **Persistence Layer** (JobRepository)
   - Responsibility: Database access, thread-safe writes
   - Boundary: SQL/prepared statements only

**Anti-Pattern:** Mixing layers (e.g., HTTP directly in pipeline stage) → tangled, hard to test.

---

### 2.3 Thread Safety by Design (Not After-the-Fact)

**Principle:** Thread safety mechanisms are chosen during design, not added later.

**Strategy:** Partition work by stage

```
Stage 1: 10 threads, but output is single queue (thread-safe by design)
Stage 2: 3 threads, input queue is bounded (backpressure enforced)
Stage 3: 1 thread (sequential, no lock needed for writes)
        + ReadWriteLock for aggregate reads from HTTP layer
```

**Why this works:**
- Stage 1 workers never race (each has own HTTP connection)
- Stage 2 input queue is thread-safe (LinkedBlockingQueue)
- Stage 3 executor is single-threaded (no concurrent storage access)
- Aggregate is protected by lock (multiple readers, occasional writers)

---

### 2.4 Fail-Soft (Graceful Degradation)

**Principle:** Failure in one URL doesn't block the entire batch.

**Implementation:**
```
URL fetch fails (timeout, invalid scheme, oversized)
  → Mark ItemStatus as FAILED
  → Add error message
  → Continue with next URL
  → Pipeline continues (don't block other stages)
```

**Consequence:** Job may complete with some errors; frontend shows per-item status.

---

### 2.5 Observability & Monitoring

**Principle:** System state is queryable in real-time (for frontend and ops).

**Mechanisms:**
1. **API:** GET /jobs/:id returns current state (items, aggregates)
2. **WebSocket:** Real-time stream of events (item completion, aggregate updates)
3. **Logging:** DEBUG logs queue depths every 1 second
4. **Metrics:** (Optional) Micrometer for latency histograms

---

## 3. Core Patterns & Their Application

### 3.1 Producer-Consumer (Stage 1 → Stage 2)

**Pattern:** One or more producers (Stage 1) push items to a bounded queue; consumers (Stage 2) pull items.

**Key Feature:** Automatic backpressure; producers block when queue is full.

**Code Structure:**
```
Stage 1 (Producer):
  for url in urls:
    item = fetch(url)
    stage2Queue.put(item)  // Blocks if queue full (bounded!)

Stage 2 (Consumer):
  while true:
    item = stage2Queue.take()  // Blocks if queue empty
    analyze(item)
    stage3Queue.put(result)
```

**Why It Works:**
- LinkedBlockingQueue(5) is bounded → automatic backpressure
- put() blocks if queue full → Stage 1 pauses
- take() blocks if empty → Stage 2 sleeps, saves CPU
- FIFO order → items processed in order

---

### 3.2 Thread Pool with Bounded Queue (Stage 1 Fetch)

**Pattern:** Fixed-size thread pool with queue of tasks.

**Configuration:**
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
// OR more explicit:
new ThreadPoolExecutor(
  corePoolSize: 10,
  maxPoolSize: 10,
  keepAliveTime: 0,
  queue: LinkedBlockingQueue<>()
);
```

**Why This Size:**
- 10 threads for I/O-bound work (network latency is hiding factor)
- Each thread can wait on a socket without blocking others
- 10 URLs concurrently is reasonable for a test

**Rejection Policy:**
```java
executor.setRejectedExecutionHandler(
  new ThreadPoolExecutor.CallerRunsPolicy()  // Caller thread does the work
);
```

---

### 3.3 Single-Threaded Executor (Stage 3 Store)

**Pattern:** Executor with exactly 1 thread guarantees sequential execution.

**Why Sequential?**
- Database writes need coordination (aggregates)
- No race conditions if single writer
- Easier to reason about correctness

**Code:**
```java
ExecutorService executor = Executors.newSingleThreadExecutor();
executor.submit(() -> {
  // This code runs exactly once, sequentially
  // Next task waits for this to complete
});
```

---

### 3.4 Read-Write Lock (Aggregate Access)

**Pattern:** Multiple readers (frontend polls), occasional writers (Stage 3).

**Why Not Simple Synchronized?**
- Synchronized is writer-locked (all readers wait)
- Read-Write lock allows concurrent readers
- Writers get exclusive access (no data corruption)

**Code:**
```java
private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

void updateAggregate(AnalysisResult result) {
  lock.writeLock().lock();
  try {
    aggregate.documentsProcessed++;
    aggregate.averageReadability = ...;
  } finally {
    lock.writeLock().unlock();
  }
}

JobAggregate readAggregate() {
  lock.readLock().lock();
  try {
    return copy(aggregate);
  } finally {
    lock.readLock().unlock();
  }
}
```

---

### 3.5 WebSocket for Real-Time Streaming

**Pattern:** Server-to-client push notifications via persistent TCP connection.

**Why WebSocket (not polling)?**
- Polling: client asks "any updates?" every 100ms → wasted requests
- WebSocket: server notifies immediately → lower latency, lower bandwidth
- Bidirectional: could add commands later (cancel job, etc.)

**Message Structure:**
```json
{
  "eventType": "item_update" | "aggregate_update" | "job_complete",
  "jobId": "abc-123",
  "itemStatus": { ... },    // if item_update
  "aggregates": { ... },    // if aggregate_update
  "timestamp": 1626...000
}
```

---

### 3.6 Immutable Data Transfer Objects

**Pattern:** Objects passed between stages are immutable or copied.

**Why?**
- Prevents accidental mutation by downstream stages
- No need for defensive copies
- Safe to share across threads

**Code:**
```java
// Anti-pattern: mutable, shared
class MutableDocument {
  List<String> links;  // Can be modified by accident
}

// Pattern: immutable
record FetchResult(
  String url,
  String content,
  FetchStatus status,
  String error
) {}  // All fields final, no setters
```

---

## 4. Data Flow Diagrams

### 4.1 Happy Path (URL Successfully Processed)

```
User submits 15 URLs
    ↓
POST /jobs
    ↓
[T0] JobService.startJob() → create job record, start async
    ↓
[T1] Stage 1 starts fetching (10 threads)
    ├─ Thread 1: fetch URL 0 (2s) → queue
    ├─ Thread 2: fetch URL 1 (1.5s) → queue
    ├─ ...
    └─ Thread 10: fetch URL 9 (1s) → queue
    ↓
[T2] Stage 2 starts analyzing (3 threads)
    ├─ Thread 1: analyze URL 0 (500ms) → queue
    ├─ Thread 2: analyze URL 1 (400ms) → queue
    ├─ Thread 3: analyze URL 2 (450ms) → queue
    ↓
[T3] Backpressure: Stage 1 queue reaches 5, Stage 1 pauses
    (Stage 2 not keeping up due to only 3 threads)
    ↓
[T4] Stage 2 drains items, Stage 1 resumes
    ↓
[T5] Stage 3 (1 thread) stores results sequentially
    ├─ Write URL 0 result to DB
    ├─ Update aggregate (total=1, avg=25.3)
    ├─ Broadcast WebSocket event: item_complete, aggregate_update
    ├─ Write URL 1 result to DB
    ├─ Update aggregate (total=2, avg=26.1)
    ├─ Broadcast WebSocket event
    └─ ...
    ↓
[T6] Frontend receives WebSocket events, updates UI in real-time
    ↓
[T7] All 15 URLs complete
    ↓
Job state: COMPLETED
GET /jobs/:id returns final aggregates
```

### 4.2 Error Path (One URL Fails)

```
Stage 1: fetch URL 5
    ↓
HTTP timeout (5s)
    ↓
Mark FetchResult as error: FetchStatus = FETCH_TIMEOUT
    ↓
Queue to Stage 2 (even though error)
    ↓
Stage 2: receives error result
    ↓
Check status: if not SUCCESS, skip analysis, pass empty AnalysisResult
    ↓
Stage 3: receives empty result
    ↓
Write to DB with empty links, empty wordFreq, score=0
    ↓
Update item_status: state = FAILED, error = "HTTP timeout"
    ↓
Broadcast WebSocket: item_update with error message
    ↓
Pipeline continues (other 14 URLs still process)
    ↓
Final aggregates computed from 14 successful URLs
```

### 4.3 Backpressure Trigger

```
Stage 1 Status: fetched 8 URLs, 5 queued to Stage 2
Stage 2 Status: analyzing URL 0, queue depth = 5
Stage 1 Status: tries to queue URL 9
    ↓
Check: stage2Queue.size() >= 5?  YES!
    ↓
Stage 1 thread blocks (Thread.sleep or wait)
    ↓
Wait for queue to drain...
    ↓
Stage 2 completes analysis of URL 0
    ↓
Stage 2 queue depth = 4
    ↓
Stage 1 resumes, queues URL 9
    ↓
Pipeline continues
```

---

## 5. API Design

### 5.1 REST Principles Applied

**Resource:** Job (one per batch)
- Identified by unique UUID
- State machine: PENDING → RUNNING → COMPLETED | FAILED
- Immutable history (don't mutate past jobs)

**Endpoints:**

```
POST /api/jobs
  Request:  { "urls": ["url1", "url2", ...] }
  Response: { "jobId": "abc-123" }
  Status:   201 Created
  Error:    400 Bad Request (invalid input)
  
GET /api/jobs/:id
  Response: { "jobId", "state", "items", "aggregates", ... }
  Status:   200 OK
  Error:    404 Not Found
  
WS /api/jobs/:id/stream
  Messages: { "eventType": "item_update" | "aggregate_update", ... }
  Lifecycle: open → receive messages → close (on job complete or disconnect)
  
GET /api/jobs
  Response: [ { "jobId", "state", "totalItems", "itemsComplete", ... }, ... ]
  Status:   200 OK
  Pagination: (optional, for future: ?page=1&size=10)
```

### 5.2 Error Responses

**Consistent Format:**
```json
{
  "error": "Human-readable message",
  "code": "ERROR_CODE",
  "requestId": "req-abc-123"
}
```

**Examples:**
```json
// 400 Bad Request
{
  "error": "Too many URLs: max 50",
  "code": "INVALID_BATCH_SIZE",
  "requestId": "req-1234"
}

// 404 Not Found
{
  "error": "Job not found",
  "code": "JOB_NOT_FOUND",
  "requestId": "req-5678"
}

// 500 Internal Server Error
{
  "error": "Internal server error",
  "code": "INTERNAL_ERROR",
  "requestId": "req-9999"
}
```

---

## 6. Frontend Architecture

### 6.1 State Machine (UI States)

```
START
  ↓
[ Form Visible ]
  │ User enters URLs, clicks Submit
  ↓
[ Submitting ] (POST /jobs)
  │ Loading spinner
  ↓
[ Progress Visible ] (WebSocket connected)
  │ Real-time item updates + aggregate updates
  │ User can navigate to other jobs (optional)
  ↓
[ Job Complete ]
  │ Show final aggregates + top words
  │ Button: "Back to Form" or "View History"
  ↓
[ History Visible ]
  │ Table of past jobs
  │ Click row to expand details
  ↓
[ Back to Form ]
  (cycle)
```

### 6.2 WebSocket Client Pattern

```javascript
class JobProgressTracker {
  constructor(jobId) {
    this.jobId = jobId;
    this.items = new Map();  // URL → ItemStatus
    this.aggregates = {};
    this.ws = null;
  }
  
  connect() {
    this.ws = new WebSocket(`ws://localhost:8080/api/jobs/${this.jobId}/stream`);
    
    this.ws.onmessage = (event) => {
      const msg = JSON.parse(event.data);
      
      if (msg.eventType === 'item_update') {
        this.updateItem(msg.itemStatus);
        this.render();  // Update table row
      } else if (msg.eventType === 'aggregate_update') {
        this.aggregates = msg.aggregates;
        this.render();  // Update aggregate display
      }
    };
    
    this.ws.onerror = () => this.handleError();
    this.ws.onclose = () => this.handleClose();
  }
  
  disconnect() {
    if (this.ws) this.ws.close();
  }
}
```

### 6.3 Real-Time Updates Without Polling

**Benefit:** Live progress without page refresh
**Mechanism:** WebSocket server pushes events

```
Frontend connects to WS
    ↓
Stage 3 completes item 0
    ↓
Stage 3 broadcasts: { eventType: 'item_update', itemStatus: {...} }
    ↓
Frontend receives, updates UI (no user action needed)
    ↓
All items processed, job complete
    ↓
Stage 3 broadcasts: { eventType: 'job_complete', aggregates: {...} }
    ↓
Frontend disables submit button, shows "Completed"
```

---

## 7. Operational Patterns

### 7.1 Graceful Startup

```
1. Spring Boot starts
2. Create DB connection pool, initialize schema
3. Create thread pools (10, 3, 1 for stages)
4. Open HTTP server on :8080
5. Ready for requests

No jobs in flight (fresh start)
Jobs persisted in DB (if restart)
```

### 7.2 Graceful Shutdown

```
1. Receive SIGTERM (kill signal)
2. Stop accepting new /jobs POST requests
3. Allow in-flight jobs to complete (drain queues)
4. Stage 1: pause fetching, wait for queued items
5. Stage 2: complete analysis of queued items
6. Stage 3: finish writing results
7. Close DB connection
8. Exit

Result: No data loss, all jobs reach stable state
```

### 7.3 Monitoring & Alerting

**Metrics to Track:**

| Metric | Threshold | Action |
|--------|-----------|--------|
| Stage 2 queue depth | >= 5 | Backpressure active (expected at volume) |
| Stage 1 thread active | == 10 for > 30s | Possible hang or slow URLs |
| Error rate | > 10% | Investigate URL list quality or network |
| Aggregate update rate | < 1/s | Possible processing slowdown |
| DB write latency | > 100ms | Investigate disk I/O |

**Logging Pattern:**
```java
// DEBUG: detailed, high-volume
log.debug("Stage 2 queue size: {}", stage2Queue.size());

// INFO: milestone events
log.info("Job {} started, {} URLs", jobId, urls.size());
log.info("Job {} completed: {} successful, {} failed", jobId, success, failed);

// WARN: degraded state
log.warn("Stage 2 queue full, backpressure active");
log.warn("URL fetch timeout: {}", url);

// ERROR: failures
log.error("Database write failed for job {}: {}", jobId, exception.getMessage());
```

---

## 8. Evolution Path

### If Time Allows (Beyond 90 Min)

**Phase 6: Scaling & Resilience**
- Horizontal scaling: multiple instances + message broker (RabbitMQ, Kafka)
- Retry logic: configurable per URL
- Job cancellation: interrupt executors, mark as CANCELLED
- Configurable concurrency: UI toggle to change thread pool sizes

**Phase 7: Monitoring & Observability**
- Micrometer metrics (latency histograms, throughput)
- Distributed tracing (if microservices later)
- Dashboards (Grafana)
- Alerts (PagerDuty)

**Phase 8: Multi-Tenancy**
- Job ownership (user ID, API key)
- Rate limiting per user
- Data isolation (query filtering by user)

---

## Summary: Design Checklist

Before implementation, verify:

- [ ] **Bounded Resources:** All queues, pools, maps have explicit limits
- [ ] **Separation of Concerns:** Layers don't mix (API, business, execution, persistence)
- [ ] **Thread Safety:** Each stage's concurrency model is explicit
- [ ] **Fail-Soft:** One URL failure doesn't block others
- [ ] **Observability:** System state is queryable (API + WebSocket)
- [ ] **Data Flow:** Unidirectional (Stage 1 → 2 → 3, no cycles)
- [ ] **Immutability:** Objects passed between stages aren't mutated
- [ ] **Error Handling:** All failure paths logged and reported to user
- [ ] **Security:** Input validation, SQL injection prevention, SSRF checks
- [ ] **Testing:** Unit tests for stages, integration tests for pipeline

This architecture prioritizes **correctness over complexity** — every mechanism has a clear purpose, and there are no speculative abstractions. Follow this design during implementation; any deviation should be questioned.

