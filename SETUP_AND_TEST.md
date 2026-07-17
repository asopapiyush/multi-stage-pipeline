# Phase 1 — Setup & Test Instructions

**This document provides step-by-step instructions to compile, test, and run the application.**

---

## Prerequisites

### Required
- **Java 17** or higher
  ```bash
  java -version
  # Output should show: openjdk version "17..." or similar
  ```

- **Maven 3.6.0** or higher
  ```bash
  mvn -version
  # Output should show: Apache Maven 3.6.x
  ```

### Optional
- **SQLite3** (for manual DB inspection)
  ```bash
  sqlite3 --version
  ```

---

## Step 1: Verify Project Structure

```bash
cd /c/Users/asopa/interview

# Should see these files:
ls -la | grep -E "(pom.xml|src|data)"

# Should show:
# -rw-r--r-- pom.xml
# drwxr-xr-x src/
```

### Verify Source Files

```bash
# Count Java files
find src -name "*.java" | wc -l
# Expected output: 20

# List all Java files
find src -name "*.java" | sort
# Expected: 13 production + 7 test files
```

---

## Step 2: Clean & Compile

```bash
# Clean previous builds
mvn clean

# Compile the project
mvn compile

# Expected output:
# [INFO] BUILD SUCCESS
```

**If you see BUILD SUCCESS, move to Step 3.**

**If you see errors:**
- Check Java version: `java -version` (must be 17+)
- Check Maven version: `mvn -version` (must be 3.6+)
- Delete `.m2` cache: `rm -rf ~/.m2/repository`
- Try again: `mvn clean compile`

---

## Step 3: Run Unit Tests

```bash
# Run all tests
mvn test

# Expected output:
# [INFO] -------------------------------------------------------
# [INFO]  T E S T S
# [INFO] -------------------------------------------------------
# [INFO] Running com.pipeline.model.JobRequestTest
# [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: X.XXX s
# [INFO] Running com.pipeline.model.FetchResultTest
# [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: X.XXX s
# [INFO] Running com.pipeline.model.AnalysisResultTest
# [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: X.XXX s
# [INFO] Running com.pipeline.model.JobAggregateTest
# [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: X.XXX s
# [INFO] Running com.pipeline.model.ItemStatusTest
# [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: X.XXX s
# [INFO] Running com.pipeline.repository.JobRepositoryTest
# [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: X.XXX s
# [INFO] Running com.pipeline.Phase1IntegrationTest
# [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: X.XXX s
# [INFO]
# [INFO] Results:
# [INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
# [INFO]
# [INFO] BUILD SUCCESS
```

### All 25 Tests Must Pass

| Test Class | Cases | Expected |
|-----------|-------|----------|
| JobRequestTest | 3 | ✅ PASS |
| FetchResultTest | 3 | ✅ PASS |
| AnalysisResultTest | 2 | ✅ PASS |
| JobAggregateTest | 3 | ✅ PASS |
| ItemStatusTest | 3 | ✅ PASS |
| JobRepositoryTest | 6 | ✅ PASS |
| Phase1IntegrationTest | 5 | ✅ PASS |
| **TOTAL** | **25** | **✅ ALL PASS** |

**If any test fails:**
1. Read the error message
2. Check the test file (src/test/java/...)
3. Check the implementation file (src/main/java/...)
4. Run the single test again: `mvn test -Dtest=TestClassName`

---

## Step 4: Build JAR

```bash
# Build the JAR file
mvn clean package

# Expected output:
# [INFO] Building jar: target/document-pipeline-1.0.0.jar
# [INFO] BUILD SUCCESS

# Verify JAR was created
ls -lh target/document-pipeline-1.0.0.jar
# Example output: -rw-r--r-- 50M document-pipeline-1.0.0.jar
```

---

## Step 5: Run the Application

### Option 1: Using Maven (Recommended for Development)

```bash
# Start the app
mvn spring-boot:run

# Expected output (wait 5-10 seconds):
# ...
# Started PipelineApplication in 2.345 seconds (JVM running for 3.456 seconds)
# 2026-07-16 14:30:00.000  INFO 12345 --- [main] com.pipeline.PipelineApplication
# 2026-07-16 14:30:00.123  INFO 12345 --- [main] o.s.b.w.e.t.TomcatWebServer
# ...
# Tomcat started on port(s): 8080 (http)
```

**Keep the terminal open while testing.**

### Option 2: Using JAR (Production-like)

```bash
# Run the JAR
java -jar target/document-pipeline-1.0.0.jar

# Expected: Same output as Option 1
# App listens on http://localhost:8080
```

---

## Step 6: Verify App is Running

### In a New Terminal

```bash
# Test if app is responding
curl http://localhost:8080

# Expected output:
# <!doctype html>
# <html>
# <head>...</head>
# <body>Welcome or 404 (depending on routes)</body>
# </html>
```

### Check Schema Initialization

```bash
# Look for this in the app logs:
# [Schema initialized successfully]

# Verify database file was created
ls -la data/pipeline.db

# Expected: Database file exists (size > 0)
```

### Inspect Database Tables

```bash
# If you have sqlite3 installed
sqlite3 data/pipeline.db

# Inside sqlite3:
sqlite> .tables
# Expected output: job_aggregates  job_items  job_results  jobs

sqlite> .schema jobs
# Expected: CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, ...)

sqlite> SELECT COUNT(*) FROM jobs;
# Expected output: 0 (no jobs yet, which is correct)

sqlite> .quit
```

---

## Step 7: Manual API Test (Optional)

### Create a Job via REST API

```bash
# In another terminal, create a job
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://example.com"]}'

# Expected output:
# {"jobId": "abc-123-def-456"} or error (depending on route implementation)

# Note: Routes are not implemented in Phase 1; this will 404 for now
```

**This is expected to fail (404) until T3.1 — POST /jobs is implemented.**

---

## Step 8: Stop the App

```bash
# Press Ctrl+C in the terminal running the app

# Expected output:
# ...
# Shutting down
# ...
# [INFO] BUILD SUCCESS (or BUILD FAILURE if interrupted)
```

---

## Troubleshooting

### Compilation Errors

**Error:** `javac: target release X does not match source release Y`
```bash
# Fix: Ensure Java 17+ is in use
java -version
# If older, set JAVA_HOME to Java 17+
export JAVA_HOME=/path/to/java-17
mvn clean compile
```

**Error:** `Could not find artifact`
```bash
# Fix: Delete Maven cache and re-download
rm -rf ~/.m2/repository
mvn clean compile
```

### Test Failures

**Error:** `Tests run: 25, Failures: 1`
```bash
# Run the failing test alone
mvn test -Dtest=TestClassName -X  # -X for debug output

# Example:
mvn test -Dtest=JobRepositoryTest -X
```

### Database Errors

**Error:** `Failed to initialize schema`
```bash
# Fix: Ensure data directory exists
mkdir -p data

# Delete corrupted database
rm -f data/pipeline.db

# Re-run tests
mvn test
```

**Error:** `sqlite-jdbc not found`
```bash
# Fix: Ensure dependency is in pom.xml
grep sqlite pom.xml

# If missing, add to pom.xml:
# <dependency>
#     <groupId>org.xerial</groupId>
#     <artifactId>sqlite-jdbc</artifactId>
#     <version>3.44.0.0</version>
# </dependency>

mvn clean compile
```

### App Won't Start

**Error:** `Port 8080 already in use`
```bash
# Fix: Stop other apps using port 8080, or use different port
export SERVER_PORT=8888
mvn spring-boot:run

# Then access: http://localhost:8888
```

**Error:** `Failed to initialize ApplicationContext`
```bash
# Fix: Check logs for root cause
# Look for [ERROR] lines in output

# Common causes:
# - Database locked (delete data/pipeline.db)
# - Missing dependency (check pom.xml)
# - Wrong Java version (check java -version)
```

---

## Success Checklist

- [ ] `mvn clean compile` → BUILD SUCCESS
- [ ] `mvn test` → 25 tests PASS
- [ ] `mvn clean package` → JAR created
- [ ] `mvn spring-boot:run` → App starts, listens on :8080
- [ ] Logs show: "[Schema initialized successfully]"
- [ ] `curl http://localhost:8080` → Response received
- [ ] `data/pipeline.db` file exists and has tables

**If all checks pass, Phase 1 is verified!**

---

## Next Steps (Phase 2)

Once Phase 1 is confirmed working:

1. **Code Review** — Run code review agent on Phase 1 code
2. **Fix Issues** — Address any code review findings
3. **Begin Phase 2** — Implement pipeline stages (T2.1–T2.4)

---

**Everything is ready. Follow these steps to verify Phase 1 works.**

