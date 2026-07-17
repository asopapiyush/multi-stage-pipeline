# Multi-Stage Parallel Document Processing Pipeline — Implementation Guide

**Status:** Ready to implement  
**Duration:** 90 minutes  
**Tech Stack:** Java/Spring Boot, SQLite, HTML/CSS/JavaScript  
**Complexity:** Medium (backpressure + concurrency + real-time streaming)  

---

## What You're Building

A web application that accepts a batch of URLs, processes them through a **three-stage concurrent pipeline**, and streams real-time progress to the browser.

**Visual Flow:**
```
User Input (URLs)
    ↓
Stage 1: Fetch (10 threads, I/O-bound)
    ↓ (Queue: max 5 items, backpressure)
Stage 2: Analyze (3 threads, CPU-bound)
    ↓
Stage 3: Store (1 thread, sequential)
    ↓
Real-Time Frontend + Database
```

---

## Documents in This Project

Read these **in order**:

1. **THIS FILE** — Overview and getting started
2. **ARCHITECTURE.md** — System design, security, thread safety (30 min read)
3. **TASKS.md** — Detailed 16-task breakdown (reference during coding)
4. **DESIGN_GUIDELINES.md** — Patterns, data flows, design principles
5. **SECURITY_AND_CODE_REVIEW.md** — Security checklist, code review rules
6. **VISUAL_REFERENCE.md** — Diagrams, state machines, decision trees
7. **IMPLEMENTATION_ROADMAP.md** — Time allocation, quick reference, gotchas

---

## Key Design Decisions

### Why This Architecture?

| Decision | Why |
|----------|-----|
| **Three stages** | Separates I/O, CPU, and storage concerns |
| **Bounded queue (5)** | Backpressure prevents memory leaks on large batches |
| **Stage 3 is single-threaded** | No race conditions on writes; safe by design |
| **ReadWriteLock on aggregate** | Multiple readers (frontend), safe writes |
| **WebSocket for updates** | Real-time, low-latency (not polling) |
| **SQLite for persistence** | Lightweight, no server, ACID compliance |

---

## Critical Success Factors

### 1. Backpressure (Non-Negotiable)

Stage 1 **must pause** when Stage 2's queue reaches 5 items. This prevents OOM on large batches.

```java
// Before submitting to thread pool:
while (stage2Queue.size() >= 5) {
  Thread.sleep(100);  // Wait for queue to drain
}
// Then submit task
```

**Verification:** Monitor logs; you should see "Stage 2 queue size: 5" repeatedly while processing.

### 2. Thread Safety (Non-Negotiable)

Aggregate updates must be protected by lock. Two threads updating simultaneously = corrupted average.

```java
aggregateLock.writeLock().lock();
try {
  aggregate.documentsProcessed++;
  aggregate.averageReadability = ...;
} finally {
  aggregateLock.writeLock().unlock();
}
```

### 3. Security Input Validation (Non-Negotiable)

- Reject `file://`, `gopher://` schemes (SSRF)
- Reject private IPs (127.0.0.1, 192.168.*, etc.)
- Reject oversized documents (>5MB)
- Use PreparedStatement (no SQL injection)

---

## Implementation Phases (90 Minutes)

### Phase 1: Foundation (20 min)
**Tasks:** T1.1 Spring Boot setup, T1.2 Data models, T1.3 SQLite persistence

**Deliverable:** Project compiles, can create job record in DB

### Phase 2: Pipeline (30 min)
**Tasks:** T2.1 Stage 1 (Fetch), T2.2 Stage 2 (Analyze), T2.3 Stage 3 (Store), T2.4 Integration

**Deliverable:** Can process 3 URLs through full pipeline

### Phase 3: REST API (20 min)
**Tasks:** T3.1 POST /jobs, T3.2 GET /jobs/:id, T3.3 WebSocket /stream, T3.4 GET /jobs

**Deliverable:** Can submit job and query status via API

### Phase 4: Frontend (15 min)
**Tasks:** T4.1 HTML form, T4.2 WebSocket updates, T4.3 Job history

**Deliverable:** Can submit URLs via UI and see progress live

### Phase 5: Testing (5 min)
**Tasks:** T5.1 E2E test with 15 URLs, T5.2 Security spot-checks

**Deliverable:** All 15 URLs process without errors

---

## Getting Started Checklist

Before you start, verify:

- [ ] Java 17+ installed
- [ ] Maven installed
- [ ] Git initialized
- [ ] ARCHITECTURE.md read (understand backpressure + three stages)
- [ ] Have test URLs: Wikipedia articles (copy from task.md)
- [ ] IDE open (IntelliJ, VS Code, or favorite)

---

## Common Pitfalls & How to Avoid Them

| Pitfall | Cause | Fix |
|---------|-------|-----|
| **Backpressure doesn't work** | Stage 2 queue not bounded to 5 | Use `LinkedBlockingQueue<>(5)` explicitly |
| **Aggregate numbers wrong** | Race condition on updates | Add `ReentrantReadWriteLock` |
| **WebSocket not updating** | Subscription map not broadcast-friendly | Store `Map<jobId, Set<WebSocketSession>>` |
| **SQL injection vulnerability** | String concatenation instead of `?` | Use `PreparedStatement` everywhere |
| **SSRF not caught** | Scheme check happens too late | Check scheme first, before any network access |
| **ReDoS timeout** | Regex on untrusted input | Cap text length + use jsoup (no regex) |
| **OOM on large batch** | Word frequency map unbounded | Cap at 100k unique words per document |
| **Slow frontend** | Polling instead of WebSocket | Use `new WebSocket(...)` for push |

---

## Code Template Snippets

### Spring Boot Application
```java
@SpringBootApplication
public class PipelineApplication {
  public static void main(String[] args) {
    SpringApplication.run(PipelineApplication.class, args);
  }
}
```

### Controller (Simple)
```java
@RestController
@RequestMapping("/api/jobs")
public class JobController {
  @PostMapping
  public ResponseEntity<?> createJob(@RequestBody JobRequest req) {
    // Validate, create job, return jobId
  }
  
  @GetMapping("/{jobId}")
  public ResponseEntity<?> getJob(@PathVariable String jobId) {
    // Query status, return JobStatus
  }
}
```

### Stage 1 Backpressure
```java
for (String url : urls) {
  // BACKPRESSURE: wait if Stage 2 queue is full
  while (stage2Queue.size() >= 5) {
    Thread.sleep(100);
  }
  
  executor.submit(() -> fetchUrl(url, stage2Queue));
}
```

### Stage 3 Lock Pattern
```java
aggregateLock.writeLock().lock();
try {
  // All updates here are atomic
  aggregate.documentsProcessed++;
  aggregate.averageReadability = newValue;
  aggregate.lastUpdated = System.currentTimeMillis();
} finally {
  aggregateLock.writeLock().unlock();
}
```

### WebSocket Handler (Broadcast)
```java
public void broadcastItemUpdate(String jobId, ItemStatus item) throws IOException {
  Set<WebSocketSession> sessions = subscriptions.getOrDefault(jobId, new HashSet<>());
  String json = objectMapper.writeValueAsString(
    new StreamMessage("item_update", jobId, item, null, System.currentTimeMillis())
  );
  
  for (WebSocketSession session : sessions) {
    if (session.isOpen()) {
      session.sendMessage(new TextMessage(json));
    }
  }
}
```

---

## Testing Your Implementation

### Quick Smoke Test
```bash
# 1. Start app
mvn spring-boot:run

# 2. In another terminal, submit job
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://en.wikipedia.org/wiki/Concurrency_(computer_science)"]}'

# 3. Get response: {"jobId": "..."}

# 4. Query status
curl http://localhost:8080/api/jobs/{jobId}

# 5. Open browser, subscribe to WebSocket
# (from browser console):
ws = new WebSocket("ws://localhost:8080/api/jobs/{jobId}/stream");
ws.onmessage = (e) => console.log(JSON.parse(e.data));
```

### Full E2E Test (15 URLs)
```bash
# Submit batch of Wikipedia URLs
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "urls": [
      "https://en.wikipedia.org/wiki/Concurrency_(computer_science)",
      "https://en.wikipedia.org/wiki/Thread_pool",
      "https://en.wikipedia.org/wiki/Producer%E2%80%93consumer_problem",
      ... (12 more URLs)
    ]
  }'

# Observe:
# 1. Queue depth oscillates (backpressure active)
# 2. Items transition through stages (FETCHING → ANALYZING → STORING → DONE)
# 3. Aggregate stats update live (WebSocket)
# 4. No exceptions in logs
# 5. All items reach DONE or FAILED
```

---

## Security Pre-Flight Checks

Before shipping, verify:

- [ ] URL validation rejects `file://` (SSRF test)
- [ ] URL validation rejects `127.0.0.1` (SSRF test)
- [ ] Content > 5MB rejected (DoS test)
- [ ] SQL injection attempt fails (try job ID with quotes)
- [ ] Job ID with `../` rejected (path traversal test)
- [ ] WebSocket only accepts valid jobIds (authorization test)

**Command to test SSRF rejection:**
```bash
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"urls": ["file:///etc/passwd"]}'

# Expected: Item marked FAILED with error "Invalid URL scheme"
```

---

## Performance Expectations

### Typical Run (15 URLs, 2–5KB each)

| Stage | Time | Notes |
|-------|------|-------|
| Stage 1 (Fetch) | 2–5 sec | Parallel HTTP requests; network is bottleneck |
| Stage 2 (Analyze) | 1–2 sec | CPU-bound; 3 workers process sequentially |
| Stage 3 (Store) | 500ms | SQLite writes, single-threaded |
| **Total** | ~8 sec | Overlapping stages (pipelined) |

### Backpressure Behavior

You should observe:
- Stage 1 threads: active (fetching URLs)
- Stage 2 queue: ~5 items (backpressure; not 0, not overflowing)
- Stage 2 threads: active (analyzing)
- Stage 3: single thread, steady writes

**Log output:**
```
[T0] Job started, 15 URLs
[T1] Stage 2 queue size: 1
[T2] Stage 2 queue size: 3
[T3] Stage 2 queue size: 5  ← Backpressure active
[T4] Stage 2 queue size: 5
[T5] Stage 2 queue size: 4
...
[Tn] Job completed: 15 successful, 0 failed
```

---

## Deployment (Bonus, if time)

### Docker

```dockerfile
FROM openjdk:17-slim
WORKDIR /app
COPY target/pipeline.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

```bash
mvn clean package
docker build -t pipeline:latest .
docker run -p 8080:8080 pipeline:latest
```

### Environment Variables

```bash
export PIPELINE_STAGE1_THREADS=10
export PIPELINE_STAGE2_THREADS=3
export PIPELINE_STAGE2_QUEUE_SIZE=5
export HTTP_TIMEOUT_SEC=5
export CONTENT_SIZE_LIMIT_MB=5
export DB_PATH=/data/pipeline.db

java -jar pipeline.jar
```

---

## Debugging Tips

### "Backpressure not activating"
1. Check: Stage 2 queue capacity is exactly 5 (`LinkedBlockingQueue(5)`)
2. Check: Stage 1 checks `stage2Queue.size() >= 5` before submitting
3. Add logging: `log.debug("Queue size: {}", stage2Queue.size())`

### "Aggregates are wrong"
1. Add logging: `log.info("Updating aggregate: count={}, avg={}", count, avg)`
2. Check: Write lock held during updates
3. Check: Read lock held when returning aggregate to frontend

### "WebSocket not sending updates"
1. Browser console: `ws.readyState` should be 1 (open)
2. Server logs: look for subscription map registration
3. Check: `broadcastItemUpdate()` is called by Stage 3

### "Database errors"
1. Check logs for SQL error messages
2. Verify: PreparedStatement used (not string concat)
3. Verify: Connections returned to pool after use

---

## Success Checklist (End of 90 Min)

- [ ] Spring Boot app compiles and runs
- [ ] Can submit 15 URLs via POST /jobs
- [ ] Can query job status via GET /jobs/:id
- [ ] WebSocket broadcasts real-time updates
- [ ] All 3 stages process concurrently
- [ ] Backpressure visible in logs (queue oscillates ~5)
- [ ] Frontend shows live progress (no page refresh)
- [ ] Final aggregates correct (count, average, top words)
- [ ] No SQL errors or race conditions
- [ ] All 15 URLs reach DONE or FAILED state
- [ ] Security checks pass (SSRF, injection, oversize)

---

## Next Steps After 90 Minutes

If you finish early:

1. **Configurable concurrency** — Add UI sliders to change thread pool sizes
2. **Job cancellation** — Gracefully stop in-flight job
3. **Retry logic** — 2x retry on transient timeouts
4. **Metrics** — Expose queue depths, latencies via Micrometer
5. **Dockerize** — Build image, docker-compose

---

## Questions to Ask Yourself

Before submitting, ask:

1. **Backpressure:** Does Stage 1 pause when Stage 2 queue hits 5?
2. **Thread safety:** Are aggregate updates protected by lock?
3. **Security:** Would SSRF attempt be rejected?
4. **Real-time:** Do frontend updates appear without refresh?
5. **Correctness:** Do aggregates match manual count?

If yes to all: **you're done!**

---

## Final Thoughts

This project exercises:
- **Concurrency:** Thread pools, bounded queues, backpressure
- **Thread safety:** Locks, atomic operations, immutable data
- **REST API:** HTTP endpoints, request validation
- **Real-time systems:** WebSocket, event broadcasting
- **Security:** Input validation, SSRF, SQL injection prevention
- **Systems thinking:** Data flows, queue depths, bottleneck identification

**The 90-minute constraint forces you to make pragmatic choices:**
- Simple algorithms (not fancy)
- Minimal dependencies (Spring + jsoup only)
- Focus on correctness (not optimization)
- One-thread for sequential work (no premature scaling)

**This is intentional.** Ship the lazy version, fix it once, move on.

---

## Document Map (For Quick Reference)

| Need | Read This |
|------|-----------|
| High-level overview | THIS FILE |
| System design + security | ARCHITECTURE.md |
| Task-by-task breakdown | TASKS.md |
| Patterns + flows | DESIGN_GUIDELINES.md |
| Security checklist | SECURITY_AND_CODE_REVIEW.md |
| Diagrams + visuals | VISUAL_REFERENCE.md |
| Time allocation + gotchas | IMPLEMENTATION_ROADMAP.md |

---

**Ready to start? Begin with Phase 1, Task T1.1 (Spring Boot Setup).**

**Estimated time to first working code: 20 minutes.**

**Estimated time to full pipeline: 50 minutes.**

**Estimated time to live frontend: 85 minutes.**

**Estimated time to secure hardening: 90 minutes.**

**Go build it.** ✓

