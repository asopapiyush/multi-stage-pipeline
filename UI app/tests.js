/**
 * DeepFlow UI Test Suite
 * A simple browser-based test suite for testing validation and logic.
 */

const Suite = {
  tests: [],
  
  add(name, fn) {
    this.tests.push({ name, fn });
  },

  async run(onComplete, onAssert) {
    let passed = 0;
    let failed = 0;
    
    for (const test of this.tests) {
      try {
        await test.fn({
          assert(condition, message) {
            if (!condition) throw new Error(message || "Assertion failed");
            onAssert(test.name, true, null);
          },
          assertEqual(a, b, message) {
            if (a !== b) throw new Error(message || `Assertion failed: expected ${b}, got ${a}`);
            onAssert(test.name, true, null);
          }
        });
        passed++;
      } catch (err) {
        failed++;
        onAssert(test.name, false, err.message);
      }
    }
    
    onComplete(passed, failed);
  }
};

// --- Test Definitions ---

Suite.add("URL Validation - Happy Path", (t) => {
  const r1 = validateUrl("https://en.wikipedia.org/wiki/Concurrency");
  t.assert(r1.valid, "Valid public HTTPS URL should pass");
  
  const r2 = validateUrl("http://example.com");
  t.assert(r2.valid, "Valid public HTTP URL should pass");
});

Suite.add("URL Validation - Protocol Check", (t) => {
  const r1 = validateUrl("ftp://files.example.com");
  t.assert(!r1.valid, "FTP scheme should be rejected");
  t.assertEqual(r1.error, "Only HTTP/HTTPS protocols allowed", "FTP error message match");
  
  const r2 = validateUrl("file:///etc/passwd");
  t.assert(!r2.valid, "File scheme should be rejected");
});

Suite.add("URL Validation - SSRF Detection", (t) => {
  const r1 = validateUrl("http://localhost:8080/admin");
  t.assert(!r1.valid, "Localhost should be rejected");
  t.assertEqual(r1.error, "Private IP addresses (SSRF hazard) are rejected", "SSRF error message match");

  const r2 = validateUrl("https://127.0.0.1/dashboard");
  t.assert(!r2.valid, "127.0.0.1 should be rejected");

  const r3 = validateUrl("http://192.168.1.50/status");
  t.assert(!r3.valid, "Private network IP should be rejected");
  
  const r4 = validateUrl("http://10.0.0.1");
  t.assert(!r4.valid, "Class A private IP should be rejected");
});

Suite.add("URL Validation - Malformed URL", (t) => {
  const r1 = validateUrl("not_a_valid_url");
  t.assert(!r1.valid, "Invalid strings should fail parse");
  t.assertEqual(r1.error, "Malformed URL format", "Malformed URL error message match");
});

Suite.add("Escape HTML utility function", (t) => {
  t.assertEqual(escapeHtml("<script>"), "&lt;script&gt;", "Escape script tags");
  t.assertEqual(escapeHtml("a & b"), "a &amp; b", "Escape ampersands");
  t.assertEqual(escapeHtml('"test"'), "&quot;test&quot;", "Escape double quotes");
});

Suite.add("State initialization and skeleton mapping", (t) => {
  // Mock skeleton call
  const urlsMock = [
    { url: "https://example.com/1", valid: true },
    { url: "file:///etc/passwd", valid: false, error: "Only HTTP/HTTPS protocols allowed" }
  ];
  
  renderDashboardSkeleton("job-test-123", urlsMock);
  
  t.assertEqual(currentJobId, "job-test-123", "Current Job ID should match skeleton creation");
  t.assertEqual(activeJobData.items.length, 2, "Active job data should have 2 items");
  t.assertEqual(activeJobData.items[0].stage, "QUEUED", "Item 1 should start in QUEUED stage");
  t.assertEqual(activeJobData.items[1].state, "FAILED", "Item 2 should start in FAILED state due to validation failure");
  t.assertEqual(activeJobData.aggregates.documentsErrored, 1, "Documents errored aggregate should be 1");
});
