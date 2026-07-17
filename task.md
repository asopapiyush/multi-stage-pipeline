# Task: Multi-Stage Parallel Document Processing Pipeline

**Time:** 90 minutes  
**Tools:** You must use an AI coding assistant (Cursor, Kiro, Copilot, or similar) throughout the exercise.  
**Tech stack:** Our stack is Java/Spring Boot — we'd prefer you use Java. If you're not comfortable with that, you may use another language, but please let us know why.

---

## The Task

Build a web app that accepts a batch of URLs, processes them through a three-stage concurrent pipeline, and streams progress and results to the frontend in real time.

---

## The Pipeline

The processing has **three stages**, each with different concurrency characteristics:

### Stage 1 — Fetch (I/O bound)
- Fetch the HTML/text content from each URL.
- High concurrency allowed (up to 10 concurrent fetches).
- Failed fetches should be marked as errors and not block other items.

### Stage 2 — Analyze (CPU bound)
- For each fetched document, perform text analysis: extract all links, count word frequencies, and compute a readability score (e.g., average sentence length × average word length — keep it simple).
- **Limited to 3 concurrent workers** (simulating a CPU-constrained resource).
- Stage 2 must not start processing an item until Stage 1 has produced it — implement a proper producer-consumer handoff.

### Stage 3 — Store & Aggregate (sequential, shared state)
- Write each processed result to persistent storage.
- Maintain a running aggregate: total documents processed, average readability score across all documents, top 20 words across the entire batch.
- This stage must be **thread-safe** — multiple Stage 2 workers may finish at the same time.

**Key constraint:** Stage 2's input queue is bounded (max 5 items). If it's full, Stage 1 must pause fetching until Stage 2 catches up. This prevents memory from growing unbounded on large batches.

---

## Backend

1. POST `/jobs` — accepts a list of URLs (10–50), starts the pipeline, returns a job ID.
2. GET `/jobs/:id` — returns the current state of a job (status per item, aggregate results so far).
3. WebSocket or SSE on `/jobs/:id/stream` — streams real-time progress: which stage each item is in, completions, errors, and the running aggregate.
4. GET `/jobs` — list past jobs with summary stats.
5. Persistence — SQLite, JSON file, or any lightweight store (no external DB server required).

## Frontend

1. A form to submit a list of URLs (one per line).
2. A real-time progress view showing:
   - Each item's current stage (queued → fetching → analyzing → done/error).
   - Queue depths between stages.
   - The running aggregate stats updating live.
3. Ability to view past job results.

---

## Bonus (if time allows)

- Configurable concurrency limits per stage via the API or UI
- Cancel a running job (gracefully stop all stages)
- Retry logic for transient fetch failures
- Dockerize the app

---

## Sample URLs for testing

Use these (or any public URLs) to test your pipeline:

```
https://en.wikipedia.org/wiki/Concurrency_(computer_science)
https://en.wikipedia.org/wiki/Thread_pool
https://en.wikipedia.org/wiki/Producer%E2%80%93consumer_problem
https://en.wikipedia.org/wiki/Backpressure_routing
https://en.wikipedia.org/wiki/MapReduce
https://en.wikipedia.org/wiki/Parallel_computing
https://en.wikipedia.org/wiki/Race_condition
https://en.wikipedia.org/wiki/Deadlock
https://en.wikipedia.org/wiki/Semaphore_(programming)
https://en.wikipedia.org/wiki/Message_queue
https://en.wikipedia.org/wiki/Asynchronous_I/O
https://en.wikipedia.org/wiki/Futures_and_promises
https://en.wikipedia.org/wiki/Actor_model
https://en.wikipedia.org/wiki/Coroutine
https://en.wikipedia.org/wiki/Event_loop
```

---

## Deliverable

A Git repo (or zip) containing:

1. Working code with setup instructions
2. A short README covering:
   - How to run it
   - Your approach and key decisions
   - How you implemented the pipeline stages and backpressure
   - Which parts the AI tool generated vs. what you wrote or modified yourself
   - Anything you'd do differently with more time
