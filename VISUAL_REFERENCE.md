# Visual Reference & Quick Lookup

**Project:** Multi-Stage Parallel Document Processing Pipeline  
**Purpose:** Diagrams, flowcharts, and quick visual guides  

---

## 1. System Architecture at a Glance

```
                    USER BROWSER
                         │
                    ┌────┴─────┐
                    │ HTML Form │
                    └────┬─────┘
                         │ HTTP + WebSocket
                         ↓
            ┌────────────────────────────┐
            │   Spring Boot Backend      │
            │  ┌─────────────────────┐  │
            │  │   API Controller    │  │
            │  └────────┬────────────┘  │
            │           │                │
            │  ┌────────▼────────────┐  │
            │  │  Job Service        │  │
            │  └────────┬────────────┘  │
            │           │                │
            │  ┌────────▼──────────────────────┐  │
            │  │  Pipeline Orchestrator        │  │
            │  │  ┌──────────────────────────┐ │  │
            │  │  │ Stage 1: Fetch (10 th)   │ │  │
            │  │  │ ↓ queue (5 bounded)      │ │  │
            │  │  │ Stage 2: Analyze (3 th)  │ │  │
            │  │  │ ↓ queue (unbounded)      │ │  │
            │  │  │ Stage 3: Store (1 th)    │ │  │
            │  │  │   + Aggregate + Lock     │ │  │
            │  │  └──────────────────────────┘ │  │
            │  └────────┬──────────────────────┘  │
            │           │                         │
            │  ┌────────▼────────────┐            │
            │  │  Job Repository     │            │
            │  │  (prepared SQL)     │            │
            │  └────────┬────────────┘            │
            └───────────┼────────────────────────┘
                        │ JDBC
                        ↓
                   ┌─────────────┐
                   │ SQLite DB   │
                   │(pipeline.db)│
                   └─────────────┘
```

---

## 2. Pipeline Execution Timeline (Happy Path)

```
Time    Stage 1 (10 th)    Stage 2 (3 th)     Stage 3 (1 th)    Queue Depth
────────────────────────────────────────────────────────────────────────────
T0      fetch URL 0        [idle]             [idle]            S2Q=0
        fetch URL 1
        fetch URL 2
        fetch URL 3
        fetch URL 4
        ...
        fetch URL 9

T1      [paused, S2Q=5]    analyze URL 0      [idle]            S2Q=5 (BACKPRESSURE!)
                           analyze URL 1
                           analyze URL 2

T2      [paused, S2Q=5]    [finishes 0]       [idle]            S2Q=4
        [resumes]          analyze URL 3
        fetch URL 10       
        fetch URL 11

T3      [paused, S2Q=5]    [finishes 1]       store result 0    S2Q=4
                           analyze URL 4      + update aggregate

T4      [continuing]       [finishes 2]       store result 1    S2Q=3
                           [idle, waiting]    + update aggregate

...continues until all items complete
```

**Key Observation:** Stage 2 queue stays around 5 (backpressure active) ✓

---

## 3. Backpressure Mechanism (Zoom In)

```
Stage 1 Thread (trying to fetch URL 10):

  Check: stage2Queue.size() >= 5?
    ├─ YES → Wait (Thread.sleep(100))
    │        ↓
    │   Continue checking...
    │        ↓
    │   Stage 2 consumes an item (queue drains)
    │        ↓
    │   Re-check: stage2Queue.size() >= 5?
    │        ├─ NO → Proceed with fetch
    │        └─ YES → Wait again
    │
    └─ NO → Fetch URL 10
            ↓
         stage2Queue.offer(result)
            ↓
         Result now in queue, Stage 2 will consume
```

**Effect:** Prevents unbounded memory growth on large batches ✓

---

## 4. Race Condition & Lock Protection

```
WITHOUT LOCK (WRONG):
┌──────────────────┐
│ Thread A         │ Thread B
├──────────────────┤
│ Read: count = 5  │ Read: count = 5
│ Read: sum = 100  │ Read: sum = 100
│                  │ avg = 100/5 = 20.0
│ avg = 100/5      │ Write: avg = 20.0
│ Write: avg = 20.0│
│                  │
│ avg field corrupted! One update lost!
└──────────────────┘


WITH READ-WRITE LOCK (CORRECT):
┌───────────────────────────────────────┐
│ Thread A (has write lock)             │
├───────────────────────────────────────┤
│ Read: count = 5                       │
│ Read: sum = 100                       │
│ avg = 100/5 = 20.0                    │
│ Write: avg = 20.0                     │
│ Release write lock                    │
│                                       │
│ [Thread B acquires write lock]        │
│ Read: count = 6                       │
│ Read: sum = 126                       │
│ avg = 126/6 = 21.0                    │
│ Write: avg = 21.0                     │
│ Release write lock                    │
│                                       │
│ ✓ Both updates applied correctly!
└───────────────────────────────────────┘
```

---

## 5. Job State Machine

```
                    ┌─────────────┐
                    │   PENDING   │
                    └──────┬──────┘
                           │ JobService.startJob()
                           ↓
                    ┌─────────────┐
                    │   RUNNING   │
                    └──┬──────┬───┘
                       │      │
        All items      │      └─ Some items FAILED
        SUCCESS        │         or timeout
                       │
                       ↓
                ┌──────────────┐
                │  COMPLETED   │
                └──────────────┘
```

**Transitions:**
- PENDING → RUNNING: Immediately on job creation
- RUNNING → COMPLETED: When all Stage 3 writes finish
- Any stage error: Item marked FAILED, pipeline continues

---

## 6. Data Structure: FetchResult → AnalysisResult → Persistence

```
┌─────────────────────────────────┐
│ URL: "https://example.com"      │
└────────────┬────────────────────┘
             │
             ↓ (Stage 1 fetches)
┌─────────────────────────────────────┐
│ FetchResult                         │
│  url: "https://example.com"        │
│  content: "<html>...</html>" (5KB) │
│  status: SUCCESS                   │
│  error: null                        │
└────────────┬────────────────────────┘
             │
             ↓ (Stage 2 analyzes)
┌──────────────────────────────────────┐
│ AnalysisResult                       │
│  url: "https://example.com"         │
│  links: ["/page1", "/page2", ...]  │
│  wordFreq: {"hello":5, ...}        │
│  readabilityScore: 24.8             │
└────────────┬─────────────────────────┘
             │
             ↓ (Stage 3 stores)
        ┌─────────────┐
        │ SQLite DB   │
        │             │
        │ jobs table  │ (1 row)
        │ job_items   │ (1 row, DONE)
        │ job_results │ (1 row)
        │ aggregates  │ (updated)
        └─────────────┘
```

---

## 7. WebSocket Event Flow

```
Frontend (Browser)          Stage 3 (Server)
─────────────────────────────────────────────
    │                              │
    ├─ ws.connect()               │
    │─────────────────────────────→│
    │                         Accept connection,
    │                         add to subscriptions[jobId]
    │                              │
    │                         ┌────┴─── Completes item 0
    │                         │ Updates aggregate under lock
    │                         │ Calls broadcastItemUpdate()
    │←─────────────────────────── {eventType: item_update, ...}
    │ Receives message            │
    │ Updates table row (URL 0)    │
    │ Re-renders                   │
    │                              │
    │                         ┌────┴─── Completes item 1
    │                         │ Calls broadcastAggregateUpdate()
    │←─────────────────────────── {eventType: aggregate_update, ...}
    │ Receives message            │
    │ Updates aggregate display    │
    │ Re-renders                   │
    │                              │
    │                         ┌────┴─── All items done
    │                         │ Calls broadcastJobComplete()
    │←─────────────────────────── {eventType: job_complete, ...}
    │ Receives message            │
    │ Shows "Completed" button     │
    │ Disables submit              │
    │                              │
    ├─ ws.close()                │
    │─────────────────────────────→│
    │                         Remove from subscriptions
    │                              │
```

---

## 8. Security Validation Flow

```
User submits URLs
    │
    ├─ URL length check
    │  ├─ YES (>2048) → REJECT
    │  └─ NO → next
    │
    ├─ Scheme validation
    │  ├─ NOT http:// or https:// → REJECT
    │  └─ YES → next
    │
    ├─ SSRF check: private IP?
    │  ├─ 127.0.0.1, 192.168.*, 10.*, 172.16.* → REJECT
    │  └─ Public IP → next
    │
    ├─ Domain whitelist (optional)
    │  ├─ NOT in whitelist → REJECT
    │  └─ In whitelist → next
    │
    └─ Fetch with timeout (5s)
       ├─ Content > 5MB → REJECT
       └─ Content <= 5MB → Process

Result: ItemStatus.state = SUCCESS or FAILED
         Frontend shows per-URL error if FAILED
```

---

## 9. Thread Pool Lifecycle

```
Application Start
    │
    ├─ FetchStage
    │  └─ new ThreadPoolExecutor(corePoolSize=10, maxPoolSize=10)
    │     └─ [Thread-1, Thread-2, ..., Thread-10] (idle, waiting for tasks)
    │
    ├─ AnalyzeStage
    │  └─ new ThreadPoolExecutor(corePoolSize=3, maxPoolSize=3)
    │     └─ [Thread-1, Thread-2, Thread-3] (idle, waiting for input queue)
    │
    └─ StoreStage
       └─ Executors.newSingleThreadExecutor()
          └─ [Thread-1] (idle, waiting for results)

Job submitted: 15 URLs
    │
    ├─ Stage 1: submit 15 tasks to executor
    │  ├─ [Thread-1 fetches URL 0] (HTTP GET, blocking I/O)
    │  ├─ [Thread-2 fetches URL 1]
    │  ├─ ... (up to 10 concurrent)
    │  └─ [Threads queue at capacity, rest wait]
    │
    ├─ Stage 2: pull from input queue
    │  ├─ [Thread-1 analyzes URL 0] (CPU-bound, ~500ms)
    │  ├─ [Thread-2 analyzes URL 1]
    │  ├─ [Thread-3 analyzes URL 2]
    │  └─ [3 threads working, input queue has 2+ waiting]
    │
    └─ Stage 3: process results
       └─ [Thread-1 writes result 0 to DB + updates aggregate] (~50ms)
          [Thread-1 writes result 1 to DB + updates aggregate]
          ...

Job complete
    │
    ├─ FetchStage.shutdown()
    │  └─ executor.shutdown(); awaitTermination(30s)
    │
    ├─ AnalyzeStage.shutdown()
    │  └─ executor.shutdown(); awaitTermination(30s)
    │
    └─ StoreStage.shutdown()
       └─ executor.shutdown(); awaitTermination(30s)
```

---

## 10. Error Handling Paths

```
URL: "https://example.com"

Path 1: Fetch Timeout
  ├─ Stage 1: GET request exceeds 5s
  ├─ Catch TimeoutException
  ├─ Create FetchResult(status=FETCH_TIMEOUT, error="...")
  ├─ Queue to Stage 2
  ├─ Stage 2: Check status, return empty result
  ├─ Stage 3: Mark as FAILED in DB
  └─ WebSocket: {eventType: item_update, state: FAILED}

Path 2: SSRF Attempt (file://)
  ├─ Stage 1: validateUrl() checks scheme
  ├─ Throw SecurityException
  ├─ Catch, create FetchResult(status=INVALID_URL)
  ├─ Queue to Stage 2
  ├─ Stage 2: Check status, return empty result
  ├─ Stage 3: Mark as FAILED in DB
  └─ WebSocket: {eventType: item_update, state: FAILED, error: "Invalid URL scheme"}

Path 3: Oversized Content
  ├─ Stage 1: Check Content-Length header
  ├─ > 5MB? Throw IOException
  ├─ Catch, create FetchResult(status=CONTENT_SIZE_EXCEEDED)
  ├─ Queue to Stage 2
  ├─ Stage 2: Check status, return empty result
  ├─ Stage 3: Mark as FAILED in DB
  └─ WebSocket: {eventType: item_update, state: FAILED}

Path 4: Database Error (rare)
  ├─ Stage 3: Database write throws SQLException
  ├─ Log error (don't lose data)
  ├─ Retry or skip
  ├─ Mark item as ERRORED in memory
  └─ WebSocket: {eventType: item_update, state: FAILED, error: "DB error"}

All paths: Pipeline continues; one failure doesn't block others ✓
```

---

## 11. API Contract (Request/Response Examples)

```
┌─────────────────────────────────────────────────────────────┐
│ POST /api/jobs                                              │
├─────────────────────────────────────────────────────────────┤
│ Request Body:                                               │
│ {                                                           │
│   "urls": [                                                 │
│     "https://en.wikipedia.org/wiki/Concurrency",           │
│     "https://en.wikipedia.org/wiki/Thread_pool",           │
│     "https://en.wikipedia.org/wiki/Producer-consumer"      │
│   ]                                                         │
│ }                                                           │
│                                                             │
│ Response (201 Created):                                     │
│ {                                                           │
│   "jobId": "550e8400-e29b-41d4-a716-446655440000"         │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ GET /api/jobs/550e8400-e29b-41d4-a716-446655440000         │
├─────────────────────────────────────────────────────────────┤
│ Response (200 OK):                                          │
│ {                                                           │
│   "jobId": "550e8400-e29b-41d4-a716-446655440000",        │
│   "state": "RUNNING",                                       │
│   "items": [                                                │
│     {                                                       │
│       "index": 0,                                           │
│       "url": "https://en.wikipedia.org/wiki/Concurrency",  │
│       "stage": "ANALYZING",                                 │
│       "state": "IN_PROGRESS",                               │
│       "error": null,                                        │
│       "startTime": 1626480000000,                           │
│       "endTime": null                                       │
│     },                                                      │
│     {                                                       │
│       "index": 1,                                           │
│       "url": "https://en.wikipedia.org/wiki/Thread_pool",   │
│       "stage": "DONE",                                      │
│       "state": "SUCCESS",                                   │
│       "error": null,                                        │
│       "startTime": 1626480001000,                           │
│       "endTime": 1626480003500                              │
│     }                                                       │
│   ],                                                        │
│   "aggregates": {                                           │
│     "documentsProcessed": 1,                                │
│     "averageReadability": 25.3,                             │
│     "topWords": {                                           │
│       "concurrency": 45,                                    │
│       "thread": 38,                                         │
│       "synchronization": 23,                                │
│       ...                                                   │
│     }                                                       │
│   },                                                        │
│   "createdAt": 1626480000000,                               │
│   "updatedAt": 1626480003500                                │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ WS /api/jobs/550e8400.../stream                             │
├─────────────────────────────────────────────────────────────┤
│ Message 1 (item complete):                                  │
│ {                                                           │
│   "eventType": "item_update",                               │
│   "jobId": "550e8400-e29b-41d4-a716-446655440000",        │
│   "itemStatus": {                                           │
│     "index": 1,                                             │
│     "url": "https://en.wikipedia.org/wiki/Thread_pool",     │
│     "stage": "STORING",                                     │
│     "state": "IN_PROGRESS"                                  │
│   },                                                        │
│   "timestamp": 1626480003000                                │
│ }                                                           │
│                                                             │
│ Message 2 (aggregate update):                               │
│ {                                                           │
│   "eventType": "aggregate_update",                          │
│   "jobId": "550e8400-e29b-41d4-a716-446655440000",        │
│   "aggregates": {                                           │
│     "documentsProcessed": 2,                                │
│     "averageReadability": 26.1,                             │
│     "topWords": { "concurrency": 45, ... }                  │
│   },                                                        │
│   "timestamp": 1626480003500                                │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 12. Quick Decision Tree: "What to Do When..."

```
┌─ "Pipeline is slow"
│  ├─ Check: Stage 1 queue size? → If small, check network
│  ├─ Check: Stage 2 queue size? → If = 5, backpressure active (expected)
│  └─ Check: CPU usage? → If low, I/O is bottleneck (normal)
│
├─ "Some URLs fail"
│  ├─ Check: Item status = FAILED? → Yes, check error message
│  ├─ "Network timeout" → Increase timeout or retry
│  ├─ "Invalid URL scheme" → Security check working ✓
│  └─ Pipeline continues ✓
│
├─ "Aggregates look wrong"
│  ├─ Check: Average readability < 0 or > 1000? → Bug in formula
│  ├─ Check: Top words list empty? → No documents processed
│  └─ Check: Word counts don't add up? → Race condition (add lock)
│
├─ "Database error"
│  ├─ Check: Error in Stage 3 logs? → SQL syntax error
│  ├─ Check: JDBC connection pool exhausted? → Increase pool size
│  └─ Check: Disk full? → Free space
│
├─ "WebSocket not connecting"
│  ├─ Browser console: Check ws.readyState (0=connecting, 1=open)
│  ├─ Check: Server logs for errors?
│  └─ Check: CORS configured for /stream endpoint?
│
└─ "Memory usage high"
   ├─ Check: Stage 2 input queue full? → Backpressure failure
   ├─ Check: Word frequency map > 100k? → Cap is broken
   └─ Check: Many jobs in memory? → Cleanup completed jobs

```

---

## Summary: Everything at a Glance

| Aspect | Key Detail |
|--------|-----------|
| **Concurrency Model** | Stage 1: 10 threads, Stage 2: 3 threads, Stage 3: 1 thread |
| **Backpressure Mechanism** | Stage 2 queue bounded at 5; Stage 1 checks size before offering |
| **Thread Safety** | ReentrantReadWriteLock on aggregate; PreparedStatement on DB |
| **Error Handling** | One URL failure doesn't block others; mark FAILED, continue |
| **Real-Time Updates** | WebSocket broadcasts events; no polling |
| **Security First** | URL validation, SSRF checks, SQL injection prevention |
| **Monitoring** | Queue sizes logged; per-item status tracked; aggregates queryable |
| **Time Limit** | 90 minutes; 5 phases, 16 tasks, ~95% code by T4.3 |

---

**All diagrams and references are visual aids only. See ARCHITECTURE.md for detailed specifications.**

