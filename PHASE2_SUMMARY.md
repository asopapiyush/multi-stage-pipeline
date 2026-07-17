# Phase 2 — Pipeline Architecture (TDD Complete)

**Status:** ✅ IMPLEMENTATION COMPLETE — READY FOR CODE REVIEW  
**Approach:** Test-Driven Development (TDD)  
**Date:** 2026-07-16  
**Time Estimate:** 30 minutes  

---

## What Was Created

### T2.1 — Stage 1: Fetch (I/O-Bound)

**FetchStageTest.java** — 8 test cases
- Valid URL processing
- Invalid URL scheme rejection (SSRF)
- Private IP rejection
- Backpressure mechanism
- Multiple URLs processing
- URL length validation
- Empty URL list handling
- Fetch timeout marking

**FetchStage.java** — Implementation
- 10-thread pool for concurrent fetches
- URL validation (scheme, domain, length)
- SSRF prevention (private IP check)
- HTTP timeout (5s)
- Content size limit (5MB)
- Backpressure to Stage 2 (waits if queue >= 5)
- Error handling (non-blocking)

### T2.2 — Stage 2: Analyze (CPU-Bound)

**AnalyzeStageTest.java** — 8 test cases
- Analyze valid HTML
- Extract links from HTML
- Word frequency counting
- Readability score calculation
- Handle errors from Stage 1
- Empty HTML content
- Case-insensitive word frequency
- Bounded thread pool concurrency
- Word map size limit

**AnalyzeStage.java** — Implementation
- 3-thread pool (CPU-bound)
- Bounded input queue (5 items)
- jsoup for HTML parsing (safe, no ReDoS)
- Link extraction
- Word frequency (cap at 100k)
- Readability score: `(avg_sentence_len * avg_word_len)`
- Worker pattern with poison pill
- Error recovery

### T2.3 — Stage 3: Store (Sequential)

**StoreStageTest.java** — 7 test cases
- Process and store result
- Aggregate update
- Thread-safe aggregate updates
- Top words aggregation
- Top words limited to 20
- Read lock protection
- Aggregate is copy (no external mutation)

**StoreStage.java** — Implementation
- Single-threaded executor (sequential)
- Database writes via prepared statements
- ReentrantReadWriteLock for aggregates
- Running average calculation (Welford's)
- Top 20 words tracking
- Deep copy for external access
- Thread-safe reads/writes

### T2.4 — Integration: PipelineOrchestrator

**PipelineOrchestratorTest.java** — 4 integration tests
- Pipeline initialization
- Three stages wired correctly
- Backpressure between stages
- Graceful shutdown

**PipelineOrchestrator.java** — Implementation
- Wires all 3 stages together
- Creates inter-stage queues
- Starts stages in reverse order
- Monitors queue depths
- Graceful shutdown

---

## File Structure (Phase 2)

```
src/
├── test/java/com/pipeline/
│   ├── stage/
│   │   ├── FetchStageTest.java (8 tests)
│   │   ├── AnalyzeStageTest.java (8 tests)
│   │   └── StoreStageTest.java (7 tests)
│   └── orchestration/
│       └── PipelineOrchestratorTest.java (4 tests)
│
└── main/java/com/pipeline/
    ├── stage/
    │   ├── FetchStage.java
    │   ├── AnalyzeStage.java
    │   └── StoreStage.java
    └── orchestration/
        └── PipelineOrchestrator.java
```

**Total: 4 test files (27 tests) + 4 implementation files**

---

## Test Coverage: 27 Tests

| Test Class | Tests | Status |
|-----------|-------|--------|
| FetchStageTest | 8 | ✅ Ready |
| AnalyzeStageTest | 8 | ✅ Ready |
| StoreStageTest | 7 | ✅ Ready |
| PipelineOrchestratorTest | 4 | ✅ Ready |
| **TOTAL** | **27** | **✅ Ready** |

---

## Design Highlights

### Backpressure Mechanism ✅
- Stage 1 monitors Stage 2 queue
- If queue size >= 5, Stage 1 pauses
- Simple but effective (sleep + retry)
- Prevents unbounded memory growth

### Thread Safety ✅
- Stage 1: independent threads (no shared state)
- Stage 2: bounded queue (auto-backpressure)
- Stage 3: single-threaded + ReentrantReadWriteLock
- Aggregates protected by read/write lock

### Security ✅
- **URL Validation:** scheme, length, domain
- **SSRF Prevention:** rejects private IPs (127.0.0.1, 192.168.*, 10.*)
- **ReDoS Prevention:** jsoup + capped text length (1MB)
- **Memory Safety:** word map cap (100k), top words (20)
- **SQL Injection:** PreparedStatement on all writes

### Error Handling ✅
- One failed URL doesn't block others
- Error results queued (marked as failed)
- Pipeline continues gracefully
- Exceptions logged, not thrown

---

## Acceptance Criteria (From TASKS.md)

✅ **T2.1:** 10-thread fetch stage with backpressure  
✅ **T2.2:** 3-thread analyze stage with bounded queue  
✅ **T2.3:** Single-thread store stage with lock  
✅ **T2.4:** Stages wired with proper queue handoffs  

---

## Next Steps

1. **Code Review** (in parallel with Phase 3)
   - Review all 4 stage implementations
   - Check security, thread safety, design
   - Verify TDD applied correctly

2. **Phase 3 Implementation** (in parallel)
   - T3.1: POST /jobs endpoint
   - T3.2: GET /jobs/:id endpoint
   - T3.3: WebSocket /stream
   - T3.4: GET /jobs (history)

3. **Integration Testing**
   - End-to-end with real URLs
   - Verify backpressure active
   - Check aggregates computed correctly

---

**Phase 2 is complete and ready for code review.**

