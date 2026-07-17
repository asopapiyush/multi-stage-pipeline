# Security & Code Review Guidelines

**Project:** Multi-Stage Parallel Document Processing Pipeline  
**Focus:** Security flaws prevention, code quality, data flow integrity  

---

## 1. Security Risks & Mitigations

### 1.1 Input Validation Layer (Stage 1: Fetch)

#### Risk 1.1.1: SSRF (Server-Side Request Forgery)

**Threat:** Attacker submits `http://localhost:8080` or `http://192.168.1.1` to scan internal network or access restricted services.

**Mitigation:**
```java
private void validateUrl(String url) throws SecurityException {
  // 1. Scheme check
  if (!url.startsWith("http://") && !url.startsWith("https://")) {
    throw new SecurityException("Only HTTP(S) schemes allowed");
  }
  
  // 2. Parse URL and extract hostname
  URL parsedUrl = new URL(url);
  String host = parsedUrl.getHost();
  
  // 3. Reject private/internal IPs
  InetAddress addr = InetAddress.getByName(host);
  if (addr.isLoopbackAddress() || 
      addr.isLinkLocalAddress() || 
      addr.isPrivateAddress() ||
      addr.isAnyLocalAddress()) {
    throw new SecurityException("Internal/private IP addresses not allowed: " + host);
  }
  
  // 4. Optional: whitelist known-safe domains
  Set<String> ALLOWED_DOMAINS = Set.of("wikipedia.org", "example.com");
  boolean allowed = ALLOWED_DOMAINS.stream().anyMatch(host::endsWith);
  if (!allowed && !ALLOWED_DOMAINS.isEmpty()) {
    throw new SecurityException("Domain not whitelisted: " + host);
  }
}
```

**Code Review Checklist:**
- [ ] URL scheme validated before HTTP call
- [ ] Private IP ranges rejected (127.0.0.1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
- [ ] Loopback address rejected
- [ ] Domain whitelist applied if strict mode enabled
- [ ] InetAddress.getByName() called safely (IPv4 + IPv6)

---

#### Risk 1.1.2: URL Injection / Malformed URLs

**Threat:** Attacker sends `//example.com`, `http:example.com`, or excessively long URLs causing buffer overflow or protocol confusion.

**Mitigation:**
```java
private void validateUrl(String url) throws SecurityException {
  if (url == null || url.isEmpty()) {
    throw new SecurityException("URL is empty");
  }
  
  if (url.length() > 2048) {  // RFC 3986 suggests ~2k limit
    throw new SecurityException("URL too long: " + url.length());
  }
  
  try {
    new URL(url);  // Throws MalformedURLException if invalid
  } catch (MalformedURLException e) {
    throw new SecurityException("Malformed URL: " + e.getMessage());
  }
}
```

**Code Review Checklist:**
- [ ] URL length bounded (max 2048 chars)
- [ ] URL() constructor used for format validation
- [ ] MalformedURLException caught and re-thrown
- [ ] Null/empty checks before processing

---

#### Risk 1.1.3: HTTP Response Splitting / Deserialization Attacks

**Threat:** Malicious server responds with crafted headers or content that breaks out of parsing, or JSON deserialization triggers RCE.

**Mitigation:**
```java
private String fetchWithTimeout(String url, Duration timeout) throws IOException {
  HttpGet request = new HttpGet(url);
  request.setConfig(RequestConfig.custom()
    .setConnectTimeout((int) timeout.toMillis())
    .setSocketTimeout((int) timeout.toMillis())
    .setConnectionRequestTimeout((int) timeout.toMillis())
    .build());
  
  request.setHeader("User-Agent", "SafeBot/1.0 (fetch only)");
  request.setHeader("Accept", "text/html, application/xhtml+xml");
  request.setHeader("Accept-Encoding", "gzip");
  
  try (CloseableHttpResponse response = httpClient.execute(request)) {
    // 1. Status code check
    if (response.getStatusLine().getStatusCode() >= 400) {
      throw new IOException("HTTP " + response.getStatusLine().getStatusCode());
    }
    
    // 2. Content-Type validation
    Header contentType = response.getFirstHeader("Content-Type");
    if (contentType != null) {
      String type = contentType.getValue();
      if (!type.contains("text") && !type.contains("html")) {
        throw new IOException("Unexpected Content-Type: " + type);
      }
    }
    
    // 3. Content size limit
    Header contentLength = response.getFirstHeader("Content-Length");
    if (contentLength != null) {
      long len = Long.parseLong(contentLength.getValue());
      if (len > 5 * 1024 * 1024) {  // 5MB
        throw new IOException("Content-Length exceeds 5MB: " + len);
      }
    }
    
    // 4. Read with size limit
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    long bytesRead = 0;
    byte[] data = new byte[8192];
    int nRead;
    while ((nRead = response.getEntity().getContent().read(data, 0, data.length)) != -1) {
      bytesRead += nRead;
      if (bytesRead > 5 * 1024 * 1024) {
        throw new IOException("Content exceeds 5MB limit during streaming");
      }
      buffer.write(data, 0, nRead);
    }
    
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
```

**Code Review Checklist:**
- [ ] Socket timeout set (5s)
- [ ] HTTP status code validated (reject 4xx, 5xx)
- [ ] Content-Type header checked (text/html only)
- [ ] Content-Length header validated before stream
- [ ] Actual bytes read bounded (5MB limit)
- [ ] Timeouts applied to socket reads (not just connection)
- [ ] HttpClient configured with connection pooling limits

---

### 1.2 Processing Layer (Stage 2: Analyze)

#### Risk 1.2.1: Regular Expression Denial of Service (ReDoS)

**Threat:** Attacker crafts document with text like `aaaa...aaa!` (no real punctuation), causing exponential regex backtracking when computing sentence boundaries.

**Mitigation:**
```java
private double computeReadabilityScore(String text) {
  if (text == null || text.isEmpty()) return 0.0;
  
  // Limit text length to prevent runaway computation
  String truncated = text.length() > 1_000_000 
    ? text.substring(0, 1_000_000) 
    : text;
  
  // Use simple, non-backtracking split
  // SAFE: single character class, no quantifiers
  int sentenceCount = 0;
  for (char c : truncated.toCharArray()) {
    if (c == '.' || c == '!' || c == '?') {
      sentenceCount++;
    }
  }
  sentenceCount = Math.max(1, sentenceCount);
  
  // Word count: simple space split, no regex
  String[] tokens = truncated.split("\\s+", -1);
  int wordCount = 0;
  int charCount = 0;
  
  for (String token : tokens) {
    if (!token.isEmpty() && token.matches("^[a-zA-Z]+$")) {
      wordCount++;
      charCount += token.length();
    }
  }
  
  if (wordCount == 0) return 0.0;
  
  double avgSentenceLen = (double) wordCount / sentenceCount;
  double avgWordLen = (double) charCount / wordCount;
  
  return avgSentenceLen * avgWordLen;
}
```

**Code Review Checklist:**
- [ ] Text length capped (1MB)
- [ ] Regex patterns are simple (no nested quantifiers, possessive)
- [ ] Character-by-character search used instead of complex regex for sentence boundaries
- [ ] Split patterns are non-catastrophic (no lookahead/lookbehind)
- [ ] Computation time logged for debugging

---

#### Risk 1.2.2: Memory Exhaustion via Word Frequency Map

**Threat:** Document with 10M unique words (e.g., random alphanumeric strings) causes HashMap to consume unbounded memory.

**Mitigation:**
```java
private Map<String, Long> computeWordFrequencies(String text) {
  Map<String, Long> freq = new LinkedHashMap<>();
  
  String[] words = text.toLowerCase().split("\\s+", -1);
  
  for (String word : words) {
    if (word.isEmpty()) continue;
    
    // Filter: only pure alphabetic, length 2+
    if (!word.matches("^[a-z]+$") || word.length() < 2) {
      continue;
    }
    
    // Hard cap: stop adding new words at 100k unique entries
    if (freq.size() >= 100_000 && !freq.containsKey(word)) {
      log.warn("Word frequency map hit 100k unique word limit");
      continue;  // Skip this word, don't add new entry
    }
    
    freq.merge(word, 1L, Long::sum);
  }
  
  return freq;
}
```

**Code Review Checklist:**
- [ ] Word map capped at 100k entries
- [ ] Once cap is reached, new words are rejected (not added)
- [ ] Frequent words still count towards frequency
- [ ] Logging when limit hit
- [ ] Memory monitoring added (optional: expose heap usage via JMX)

---

#### Risk 1.2.3: HTML Parsing Malformations / Billion Laughs

**Threat:** Malicious HTML with nested DTDs (`<!DOCTYPE html [<!ENTITY ...>]>`) or deeply nested tags causes parser to hang or consume unbounded memory.

**Mitigation:**
```java
private List<String> extractLinks(String htmlContent) throws IOException {
  // jsoup is safe by default (no DTD processing)
  // But enforce parser settings
  
  Document doc = Jsoup.parse(
    htmlContent,
    "",
    Parser.htmlParser()  // Use HTML parser, not XML
  );
  
  // Jsoup automatically limits nesting depth; max ~100 levels
  // No additional configuration needed; jsoup is hardened against XXE
  
  return doc.select("a[href]")
            .stream()
            .map(el -> {
              String href = el.attr("href");
              
              // Sanitize: reject javascript:, data: URLs
              if (href.startsWith("javascript:") || href.startsWith("data:")) {
                return null;
              }
              
              return href;
            })
            .filter(href -> href != null && !href.isEmpty())
            .collect(Collectors.toList());
}
```

**Code Review Checklist:**
- [ ] jsoup used (not regex-based HTML parsing)
- [ ] XML parser not used (HTML parser forced)
- [ ] javascript: and data: URLs rejected
- [ ] No custom SAX configuration with DTD processing
- [ ] Nesting depth implicitly limited by jsoup (verification step: test with deeply nested HTML)

---

### 1.3 Storage Layer (Stage 3: Store)

#### Risk 1.3.1: SQL Injection

**Threat:** Attacker submits job ID like `'; DROP TABLE jobs; --` which, if concatenated into SQL, corrupts database.

**Mitigation:**
```java
public void saveResult(String jobId, AnalysisResult result) {
  // SAFE: Prepared statement with parameterized queries
  String sql = "INSERT INTO job_results (job_id, url, links, word_freq, readability_score) "
             + "VALUES (?, ?, ?, ?, ?)";
  
  try (Connection conn = dataSource.getConnection();
       PreparedStatement ps = conn.prepareStatement(sql)) {
    
    ps.setString(1, jobId);          // Parameterized
    ps.setString(2, result.url);     // Parameterized
    ps.setString(3, JSON.serialize(result.links));      // Parameterized
    ps.setString(4, JSON.serialize(result.wordFrequencies));  // Parameterized
    ps.setDouble(5, result.readabilityScore);  // Parameterized
    
    ps.executeUpdate();
  } catch (SQLException e) {
    log.error("Database error: {}", e.getMessage());
    throw new RuntimeException("Failed to save result", e);
  }
  
  // UNSAFE (NEVER DO THIS):
  // String unsafe = "INSERT INTO job_results VALUES ('" + jobId + "', ...)";
  // This is vulnerable to injection.
}
```

**Code Review Checklist:**
- [ ] All SQL uses PreparedStatement (no string concatenation)
- [ ] All user-supplied fields are parameterized (?)
- [ ] No dynamic SQL construction
- [ ] Exception handling doesn't leak SQL details to user

---

#### Risk 1.3.2: Race Condition in Aggregate Updates

**Threat:** Two Stage 2 threads finish simultaneously and both update averageReadability, causing non-atomic read-modify-write and corrupting the running average.

**Mitigation:**
```java
private final ReentrantReadWriteLock aggregateLock = new ReentrantReadWriteLock();

public void processResult(AnalysisResult result) {
  executor.submit(() -> {
    try {
      // Stage 3 is single-threaded, so writes are inherently serialized
      // But Stage 2 may offer multiple results concurrently
      // Thus we need write lock for aggregate updates
      
      aggregateLock.writeLock().lock();
      try {
        // Read-modify-write is now atomic
        int oldCount = aggregate.documentsProcessed;
        double oldAvg = aggregate.averageReadability;
        
        aggregate.documentsProcessed++;
        
        // Welford's algorithm for stable average updates
        double newAvg = (oldAvg * oldCount + result.readabilityScore) / aggregate.documentsProcessed;
        aggregate.averageReadability = newAvg;
        
        aggregate.lastUpdated = System.currentTimeMillis();
      } finally {
        aggregateLock.writeLock().unlock();
      }
      
    } catch (Exception e) {
      log.error("Error processing result: {}", e);
    }
  });
}

public JobAggregate getAggregate() {
  aggregateLock.readLock().lock();
  try {
    // Return a copy to prevent external mutation
    JobAggregate copy = new JobAggregate();
    copy.documentsProcessed = aggregate.documentsProcessed;
    copy.averageReadability = aggregate.averageReadability;
    copy.topWords = new HashMap<>(aggregate.topWords);
    copy.lastUpdated = aggregate.lastUpdated;
    return copy;
  } finally {
    aggregateLock.readLock().unlock();
  }
}
```

**Code Review Checklist:**
- [ ] Aggregate updates protected by write lock
- [ ] Reads (frontend polls) protected by read lock
- [ ] Read-modify-write is atomic (all operations inside lock)
- [ ] Copy returned from getter (no external mutation)
- [ ] Welford's algorithm used for numerical stability

---

#### Risk 1.3.3: Path Traversal (if using JSON files)

**Threat:** If job ID contains `../`, attacker could write to arbitrary locations: `job-id="../../etc/passwd"`.

**Mitigation:**
```java
private void validateJobId(String jobId) throws SecurityException {
  // Job ID must be UUID or alphanumeric + dash only
  if (!jobId.matches("^[a-zA-Z0-9-]+$")) {
    throw new SecurityException("Invalid job ID format");
  }
  
  // Double-check: no path traversal sequences
  if (jobId.contains("..") || jobId.contains("/") || jobId.contains("\\")) {
    throw new SecurityException("Job ID contains invalid characters");
  }
}

private void saveJobToJson(String jobId, JobStatus status) throws IOException {
  validateJobId(jobId);  // ← Always validate first
  
  File jobFile = new File(jobsDir, jobId + ".json");
  
  // Ensure jobFile is within jobsDir (canonical path check)
  String canonical = jobFile.getCanonicalPath();
  String parentCanonical = jobsDir.getCanonicalPath();
  if (!canonical.startsWith(parentCanonical)) {
    throw new SecurityException("Path traversal attempt detected");
  }
  
  // Now safe to write
  Files.write(jobFile.toPath(), JSON.serialize(status).getBytes());
}
```

**Code Review Checklist:**
- [ ] Job ID validated (alphanumeric + dash only)
- [ ] No `..` or `/` in job ID
- [ ] Canonical path check to prevent traversal
- [ ] File creation only within designated directory

---

### 1.4 API Layer (HTTP Endpoints)

#### Risk 1.4.1: Cross-Origin Attacks (CORS)

**Threat:** Malicious frontend at `evil.com` makes requests to our API, stealing job data via browser XSS.

**Mitigation:**
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
            .allowedOrigins("https://trusted-domain.com")  // Whitelist, not *
            .allowedMethods("GET", "POST")
            .allowedHeaders("Content-Type")
            .allowCredentials(false)  // No cookies
            .maxAge(3600);  // Preflight cache
  }
}

@RestController
class JobController {
  @PostMapping("/jobs")
  public ResponseEntity<?> createJob(@RequestBody JobRequest req, HttpServletResponse response) {
    // Add security headers
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    response.setHeader("Content-Security-Policy", "default-src 'self'");
    
    // ... create job
  }
}
```

**Code Review Checklist:**
- [ ] CORS restricted to known origins (not `*`)
- [ ] Only necessary HTTP methods allowed
- [ ] Credentials not sent with CORS requests
- [ ] Security headers set (X-Content-Type-Options, CSP, HSTS)
- [ ] Preflight requests cached

---

#### Risk 1.4.2: Denial of Service via Large Batches

**Threat:** Attacker submits `{"urls": [... 10,000 URLs ...]}`, causing memory spike and hanging.

**Mitigation:**
```java
@RestController
class JobController {
  private static final int MAX_URLS_PER_JOB = 50;
  private static final int MAX_URL_LENGTH = 2048;
  
  @PostMapping("/jobs")
  public ResponseEntity<?> createJob(@RequestBody JobRequest req) {
    if (req.urls() == null || req.urls().isEmpty()) {
      return ResponseEntity.badRequest().body("No URLs provided");
    }
    
    if (req.urls().size() > MAX_URLS_PER_JOB) {
      return ResponseEntity.status(400)
        .body("Too many URLs: max " + MAX_URLS_PER_JOB);
    }
    
    for (String url : req.urls()) {
      if (url.length() > MAX_URL_LENGTH) {
        return ResponseEntity.status(400)
          .body("URL too long: " + url.length());
      }
    }
    
    // ... proceed
  }
}
```

**Code Review Checklist:**
- [ ] Max URL count enforced (50)
- [ ] Max URL length enforced (2048 chars)
- [ ] Request size limit configured in Spring (server.tomcat.max-http-post-size)
- [ ] Rate limiting applied (optional: token bucket per IP)

---

#### Risk 1.4.3: Information Disclosure via Error Messages

**Threat:** Exception stacktraces leaked in error responses, revealing internal architecture.

**Mitigation:**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
  
  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> handleGenericException(Exception ex) {
    log.error("Unhandled exception", ex);  // Log full stacktrace internally
    
    // Return generic error to client, no stacktrace
    return ResponseEntity.status(500).body(Map.of(
      "error", "Internal server error",
      "requestId", UUID.randomUUID().toString()
    ));
  }
  
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> handleBadInput(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(Map.of(
      "error", "Invalid input: " + ex.getMessage()  // Safe message only
    ));
  }
}
```

**Code Review Checklist:**
- [ ] No stacktraces sent to clients
- [ ] Log full details internally (for debugging)
- [ ] Error messages don't reveal internals (DB schema, file paths)
- [ ] Request ID included for support tracing

---

## 2. Data Flow Security

### 2.1 Cross-Stage Contamination

**Risk:** Data corruption if Stage 2 receives partially-written data from Stage 1.

**Safeguard:**
```
Stage 1 ensures atomicity:
├─ Fetch complete
├─ Wrap in FetchResult (immutable)
└─ Offer to queue (atomic offer)

Stage 2 reads immutable FetchResult:
├─ No concurrent modifications possible
└─ Safe to analyze

Stage 3 writes to DB:
├─ All writes in single thread (no race)
└─ Aggregate updates protected by lock
```

**Code Review Checklist:**
- [ ] All data objects passed between stages are immutable or copied
- [ ] Stage 1 never partial-writes to queue
- [ ] Stage 2 doesn't modify FetchResult
- [ ] Stage 3 holds lock during aggregate update

---

### 2.2 Queue Overflow / Backpressure Failure

**Risk:** If backpressure check fails, Stage 2 queue grows unbounded, causing OOM.

**Safeguard:**
```java
// Stage 2 input queue is bounded
BlockingQueue<FetchResult> stage2Queue = new LinkedBlockingQueue<>(5);

// Stage 1 submission respects bound
while (stage2Queue.size() >= 5) {
  Thread.sleep(100);  // Backpressure: wait for queue to drain
}
stage2Queue.offer(result);

// Timeout-based fallback
boolean offered = stage2Queue.offer(result, 10, TimeUnit.SECONDS);
if (!offered) {
  log.error("Stage 2 queue full, marking item as backpressure error");
  markItemFailed(url, "BACKPRESSURE_TIMEOUT");
}
```

**Code Review Checklist:**
- [ ] Stage 2 input queue capacity is exactly 5
- [ ] Stage 1 checks queue size before submitting
- [ ] Timeout prevents infinite waits
- [ ] Failed offers are logged and tracked
- [ ] Queue size logged periodically for monitoring

---

## 3. Code Review Checklist (Per Phase)

### Phase 1: Foundation (Data Models & Persistence)

- [ ] No circular references in data models
- [ ] All `List`/`Map` fields are thread-safe (ConcurrentHashMap, List.copyOf)
- [ ] Database queries use PreparedStatement only
- [ ] Connection pool configured (max size, timeout)
- [ ] Nullable fields explicitly marked (@Nullable)
- [ ] No sensitive data in toString() methods
- [ ] Date/time fields use Instant (UTC), not Date

---

### Phase 2: Pipeline (Executors & Queues)

- [ ] ThreadPoolExecutor sizes match concurrency goals (10, 3, 1)
- [ ] Queue capacities enforced (5 for Stage 2 input)
- [ ] Backpressure implemented (Stage 1 monitors Stage 2)
- [ ] Timeouts set on all I/O operations (5s for HTTP)
- [ ] Rejected task handler configured (log or retry)
- [ ] Worker threads named descriptively (for debugging)
- [ ] No Thread.stop() or Thread.interrupt() without cleanup
- [ ] Shutdown procedures graceful (allow in-flight work to complete)

---

### Phase 3: REST API & WebSocket

- [ ] Input validation on all endpoints
- [ ] CORS configured (whitelist origins)
- [ ] Security headers set (CSP, HSTS, X-Frame-Options)
- [ ] Rate limiting considered (optional)
- [ ] WebSocket connections authenticated (if multi-user)
- [ ] Broadcast messages don't include sensitive data
- [ ] Connection cleanup on disconnect (no resource leaks)

---

### Phase 4: Frontend

- [ ] User input sanitized before display (no XSS)
- [ ] WebSocket reconnection logic (handle network failures)
- [ ] Error messages don't leak server details
- [ ] Large responses paginated (job history)
- [ ] No credentials stored in localStorage

---

### Phase 5: Testing

- [ ] Unit tests for validation logic (URL, job ID)
- [ ] Integration tests for pipeline (verify backpressure)
- [ ] Security tests (SSRF attempt, SQL injection attempt, path traversal)
- [ ] Stress tests (1000 URLs, 10M word doc)
- [ ] Coverage: all security-sensitive code >80%

---

## 4. Deployment Security

### Pre-Deployment Checklist

- [ ] All hardcoded secrets removed (use environment variables)
- [ ] Database password not in logs
- [ ] HTTPS enabled (self-signed cert for dev, proper cert for prod)
- [ ] Firewall restricts access to internal APIs (admin only)
- [ ] Log retention configured (comply with data retention policy)
- [ ] Monitoring/alerting set up (queue full, error rate spike)

---

## 5. Known Attack Vectors & Responses

| Attack | Vector | Detection | Response |
|--------|--------|-----------|----------|
| SSRF | URL to internal IP | `InetAddress.isPrivateAddress()` | Reject with SecurityException |
| ReDoS | Complex regex in sentence split | Timeout + log | Cap text length to 1MB |
| OOM | 10M unique words | Word map size check | Cap at 100k, log warning |
| SQL Injection | Job ID with quotes | PreparedStatement | Parameterized, no concatenation |
| Path Traversal | Job ID with `../` | Canonical path check + regex | Reject, log attempt |
| XXE | Nested DTDs in HTML | jsoup parser only | No custom XML config |
| CORS Bypass | Origin header mismatch | Whitelist check | Reject, return 403 |
| DoS | 10k URLs in one batch | MAX_URLS_PER_JOB = 50 | Return 400 |

---

## 6. Incident Response

### If a Security Issue Is Found

1. **Stop the deployment** (don't ship compromised code)
2. **Patch immediately** (fix the root cause, not just symptoms)
3. **Add test coverage** (prevent regression)
4. **Document in code review** (add to this checklist)
5. **Audit similar code** (grep for the pattern elsewhere)

**Example:**
```
Found: Stage 1 not validating URL scheme
→ Added URL scheme check
→ Added test: validateUrl("file:///etc/passwd") throws SecurityException
→ Audited: checked all fetch() calls now use validateUrl()
→ Updated: SECURITY_AND_CODE_REVIEW.md with SSRF mitigation
```

---

## Summary

**Security Layers:**
1. **Input:** URL validation (scheme, SSRF, length)
2. **Processing:** Resource limits (ReDoS, word maps), safe parsing (jsoup)
3. **Storage:** SQL injection prevention (PreparedStatement), race condition handling (locks)
4. **API:** CORS, rate limiting, error handling
5. **Deployment:** Secrets management, HTTPS, monitoring

**Golden Rules:**
- Validate all external input (URLs, batch sizes)
- Bound all resources (queue sizes, text length, word maps)
- Use safe libraries (jsoup, PreparedStatement, jsoup)
- Protect shared state (ReadWriteLock, single-threaded executor)
- Log suspicious activity (validation failures, resource limits hit)

Follow this checklist before code review approval.

