function backendUrl(path) {
  const base = (window.APP_CONFIG && window.APP_CONFIG.BACKEND_URL) || `http://${window.location.hostname}:8080`;
  return `${base}${path}`;
}

function authHeader() {
  const token = sessionStorage.getItem('deepflow_token');
  return token ? { 'Authorization': `Bearer ${token}` } : {};
}

function isLoggedIn() {
  return !!sessionStorage.getItem('deepflow_token');
}

// Global State Management
let currentJobId = null;
let wsConnection = null;
let jobsHistory = [];
let activeJobData = {
  urls: [],
  items: [],
  aggregates: {
    documentsProcessed: 0,
    documentsErrored: 0,
    averageReadability: 0,
    totalWordsAnalyzed: 0,
    topWords: {},
  }
};
let filterState = 'all';

// On Page Load
document.addEventListener('DOMContentLoaded', () => {
  if (isLoggedIn()) {
    showApp();
  } else {
    showLoginOverlay();
  }
});

// AUTH

function showLoginOverlay() {
  document.getElementById('loginOverlay').classList.remove('hidden');
  document.querySelector('.app-container').classList.add('auth-locked');
}

function showApp() {
  document.getElementById('loginOverlay').classList.add('hidden');
  document.querySelector('.app-container').classList.remove('auth-locked');
  loadHistoryCache();
  renderHistoryList();
  loadLiveHistory();
}

async function handleLogin(event) {
  event.preventDefault();

  const username = document.getElementById('loginUsername').value.trim();
  const password = document.getElementById('loginPassword').value;
  const errorEl = document.getElementById('loginError');
  errorEl.style.display = 'none';

  try {
    const res = await fetch(backendUrl('/api/auth/login'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });

    if (!res.ok) {
      errorEl.textContent = 'Invalid username or password.';
      errorEl.style.display = 'block';
      return false;
    }

    const data = await res.json();
    sessionStorage.setItem('deepflow_token', data.token);
    showApp();
  } catch (e) {
    errorEl.textContent = `Could not reach the server: ${e.message}`;
    errorEl.style.display = 'block';
  }

  return false;
}

function logout() {
  if (wsConnection) {
    wsConnection.close();
    wsConnection = null;
  }
  sessionStorage.removeItem('deepflow_token');
  resetDashboardView();
  showLoginOverlay();
}

function handleUnauthorized() {
  sessionStorage.removeItem('deepflow_token');
  showNotification('Session expired. Please log in again.', 'error');
  if (wsConnection) {
    wsConnection.close();
    wsConnection = null;
  }
  showLoginOverlay();
}

async function loadLiveHistory() {
  try {
    const res = await fetch(backendUrl('/api/jobs'), { headers: authHeader() });
    if (res.status === 401) { handleUnauthorized(); return; }
    if (!res.ok) return;
    const jobs = await res.json();
    jobsHistory = jobs.map(j => ({
      jobId: j.jobId,
      state: j.state,
      totalItems: j.items ? j.items.length : 0,
      itemsComplete: j.aggregates ? (j.aggregates.documentsProcessed || 0) : 0,
      itemsErrored: j.aggregates ? (j.aggregates.documentsErrored || 0) : 0,
      avgReadability: j.aggregates ? (j.aggregates.averageReadability || 0) : 0,
      totalWords: j.aggregates ? (j.aggregates.totalWordsAnalyzed || 0) : 0,
      topWords: j.aggregates ? (j.aggregates.topWords || {}) : {},
      createdAt: j.createdAt || Date.now()
    }));
    renderHistoryList();
  } catch (e) {
    // Backend not reachable; keep local history
  }
}

// Reset Dashboard Panel
function resetDashboardView() {
  if (wsConnection) {
    wsConnection.close();
    wsConnection = null;
  }
  currentJobId = null;

  const dashboard = document.getElementById('dashboardPanel');
  dashboard.classList.add('disabled');

  document.getElementById('itemsTableBody').innerHTML = '';
  document.getElementById('topWordsContainer').innerHTML = '<div class="no-words-msg">Awaiting document analysis...</div>';
  document.getElementById('docsProcessed').textContent = '0';
  document.getElementById('docsProgressText').textContent = '0 of 0 successful';
  document.getElementById('avgReadability').textContent = '0.00';
  document.getElementById('totalWords').textContent = '0';

  updateQueueDepth(0);
}

// Helper to show modern alert notifications
function showNotification(message, type = "info") {
  const toast = document.createElement('div');
  toast.style.position = 'fixed';
  toast.style.bottom = '24px';
  toast.style.right = '24px';
  toast.style.padding = '12px 20px';
  toast.style.borderRadius = '10px';
  toast.style.background = type === 'error' ? 'rgba(239, 68, 68, 0.9)' : 'rgba(99, 102, 241, 0.9)';
  toast.style.color = '#fff';
  toast.style.boxShadow = '0 10px 15px -3px rgba(0, 0, 0, 0.3)';
  toast.style.zIndex = '1000';
  toast.style.fontSize = '0.9rem';
  toast.style.backdropFilter = 'blur(10px)';
  toast.style.border = '1px solid rgba(255, 255, 255, 0.1)';
  toast.style.transition = 'all 0.3s ease';
  toast.textContent = message;

  document.body.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}

// Clear Input Textarea
function clearInput() {
  document.getElementById('urlInput').value = '';
}

// Validate URL syntax and scheme
function validateUrl(urlString) {
  try {
    const url = new URL(urlString.trim());
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return { valid: false, error: 'Only HTTP/HTTPS protocols allowed' };
    }
    // SSRF pattern checks
    const hostname = url.hostname.toLowerCase();
    if (
      hostname === 'localhost' ||
      hostname === '127.0.0.1' ||
      hostname.startsWith('192.168.') ||
      hostname.startsWith('10.') ||
      hostname.startsWith('172.16.') ||
      hostname.startsWith('172.17.') ||
      hostname.startsWith('172.18.') ||
      hostname.startsWith('172.19.') ||
      hostname.startsWith('172.20.') ||
      hostname.startsWith('172.21.') ||
      hostname.startsWith('172.22.') ||
      hostname.startsWith('172.23.') ||
      hostname.startsWith('172.24.') ||
      hostname.startsWith('172.25.') ||
      hostname.startsWith('172.26.') ||
      hostname.startsWith('172.27.') ||
      hostname.startsWith('172.28.') ||
      hostname.startsWith('172.29.') ||
      hostname.startsWith('172.30.') ||
      hostname.startsWith('172.31.')
    ) {
      return { valid: false, error: 'Private IP addresses (SSRF hazard) are rejected' };
    }
    return { valid: true };
  } catch (e) {
    return { valid: false, error: 'Malformed URL format' };
  }
}

// Submit Job
async function submitJob() {
  const inputVal = document.getElementById('urlInput').value.trim();
  if (!inputVal) {
    showNotification('Please enter at least one URL.', 'error');
    return;
  }

  const urls = inputVal.split('\n')
                       .map(u => u.trim())
                       .filter(u => u.length > 0);

  if (urls.length === 0) {
    showNotification('No valid URLs detected.', 'error');
    return;
  }

  if (urls.length > 50) {
    showNotification('A maximum of 50 URLs can be processed per batch.', 'error');
    return;
  }

  document.getElementById('submitBtn').disabled = true;
  document.getElementById('submitBtn').innerHTML = '<i class="animate-spin text-white"></i> Processing...';

  await runLiveJob(urls);
}

// RENDER FUNCTIONALITY
function renderDashboardSkeleton(jobId, urls) {
  currentJobId = jobId;
  activeJobData = {
    urls: urls,
    items: urls.map((url, idx) => ({
      index: idx,
      url: url.url,
      stage: 'QUEUED',
      state: url.valid ? 'PENDING' : 'FAILED',
      error: url.valid ? null : url.error,
      startTime: 0,
      endTime: 0,
      readabilityScore: 0
    })),
    aggregates: {
      documentsProcessed: 0,
      documentsErrored: urls.filter(u => !u.valid).length,
      averageReadability: 0,
      totalWordsAnalyzed: 0,
      topWords: {}
    }
  };

  const dashboard = document.getElementById('dashboardPanel');
  dashboard.classList.remove('disabled');

  document.getElementById('activeJobIdText').textContent = `Job Dashboard — ${jobId.substring(0, 8)}...`;

  // Set initial status badge
  const badge = document.getElementById('jobStateBadge');
  badge.className = 'status-badge RUNNING';
  document.getElementById('jobStateText').textContent = 'RUNNING';

  renderItemsTable();
  updateKPIs();
  updateTopWords();
}

function renderItemsTable() {
  const tbody = document.getElementById('itemsTableBody');
  tbody.innerHTML = '';

  activeJobData.items.forEach(item => {
    // Apply filters
    if (filterState === 'success' && item.state !== 'SUCCESS') return;
    if (filterState === 'failed' && item.state !== 'FAILED') return;

    const tr = document.createElement('tr');
    tr.tabIndex = 0;
    tr.setAttribute('role', 'button');
    tr.setAttribute('aria-label', `View details for ${item.url}`);
    tr.onclick = () => openItemDetail(item.index);
    tr.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        openItemDetail(item.index);
      }
    };

    let readabilityCell = '—';
    if (item.state === 'SUCCESS') {
      readabilityCell = item.readabilityScore.toFixed(2);
    }

    tr.innerHTML = `
      <td class="url-cell">${escapeHtml(item.url)}</td>
      <td class="stage-cell">${item.stage}</td>
      <td>
        <span class="state-cell ${item.state}">
          <span class="state-indicator"></span>
          ${item.state}
        </span>
      </td>
      <td class="readability-cell">${readabilityCell}</td>
    `;
    tbody.appendChild(tr);
  });
}

function filterItems(type, event) {
  filterState = type;
  document.querySelectorAll('.filter-badge').forEach(badge => {
    badge.classList.remove('active');
    badge.setAttribute('aria-pressed', 'false');
  });
  const target = (event && event.currentTarget) || document.querySelector(`.filter-badge.${type === 'all' ? '' : type}`);
  if (target) {
    target.classList.add('active');
    target.setAttribute('aria-pressed', 'true');
  }
  renderItemsTable();
}

function updateKPIs() {
  const processed = activeJobData.aggregates.documentsProcessed;
  const errored = activeJobData.aggregates.documentsErrored;
  const total = activeJobData.items.length;

  document.getElementById('docsProcessed').textContent = processed;
  document.getElementById('docsProgressText').textContent = `${processed} of ${total} successful (${errored} errored)`;

  document.getElementById('avgReadability').textContent = activeJobData.aggregates.averageReadability.toFixed(2);
  document.getElementById('totalWords').textContent = activeJobData.aggregates.totalWordsAnalyzed.toLocaleString();
}

function updateQueueDepth(depth) {
  document.getElementById('queueNumericText').textContent = `${depth} / 5`;
  const fill = document.getElementById('queueMeterFill');
  fill.style.width = `${(depth / 5) * 100}%`;

  // Update color style classes
  fill.className = 'meter-fill';
  if (depth === 0) fill.classList.add('fill-empty');
  else if (depth < 5) fill.classList.add('fill-partial');
  else fill.classList.add('fill-full');

  // Update dots
  for (let i = 0; i < 5; i++) {
    const dot = document.getElementById(`q-dot-${i}`);
    if (i < depth) {
      dot.className = 'queue-dot active';
    } else {
      dot.className = 'queue-dot empty';
    }
  }
}

function updateTopWords() {
  const container = document.getElementById('topWordsContainer');
  container.innerHTML = '';

  const entries = Object.entries(activeJobData.aggregates.topWords);
  if (entries.length === 0) {
    container.innerHTML = '<div class="no-words-msg">Awaiting document analysis...</div>';
    return;
  }

  // Sort and render top 20
  entries
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)
    .forEach(([word, count]) => {
      const badge = document.createElement('div');
      badge.className = 'word-badge';
      badge.innerHTML = `
        <span class="word-val">${escapeHtml(word)}</span>
        <span class="word-count">${count}</span>
      `;
      container.appendChild(badge);
    });
}

// Side Drawer for Details
function openItemDetail(index) {
  const item = activeJobData.items[index];
  const drawer = document.getElementById('itemDetailDrawer');
  const body = document.getElementById('drawerBody');

  let statsHTML = `<p class="text-muted">No analysis stats available. Document state is currently: <strong>${item.state}</strong></p>`;
  if (item.state === 'SUCCESS') {
    statsHTML = `
      <div class="stats-grid-mini">
        <div class="stat-box">
          <span class="detail-label">Readability Score</span>
          <div class="num text-pink">${item.readabilityScore.toFixed(2)}</div>
        </div>
        <div class="stat-box">
          <span class="detail-label">Words Extracted</span>
          <div class="num text-emerald">${(item.wordCount || 0).toLocaleString()}</div>
        </div>
      </div>
      <div class="detail-sec">
        <span class="detail-label">Extracted Outbound Links</span>
        <ul style="padding-left: 20px; font-size: 0.85rem; color: var(--text-muted); word-break: break-all;">
          ${(item.links || []).map(link => `<li>${escapeHtml(link)}</li>`).join('')}
        </ul>
      </div>
    `;
  } else if (item.state === 'FAILED') {
    statsHTML = `
      <div class="stat-box" style="border-color: rgba(239, 68, 68, 0.3); background: rgba(239, 68, 68, 0.02);">
        <span class="detail-label text-red">Failure Error Message</span>
        <p style="margin-top: 6px; font-size: 0.9rem; color: #f87171;">${escapeHtml(item.error || 'Unknown error occurred')}</p>
      </div>
    `;
  }

  body.innerHTML = `
    <div class="detail-sec">
      <span class="detail-label">Target URL</span>
      <div class="detail-val url-text">${escapeHtml(item.url)}</div>
    </div>

    <div class="detail-sec">
      <span class="detail-label">Pipeline Stage Timeline</span>
      <div style="font-size: 0.85rem;">
        <p>Current Stage: <strong>${item.stage}</strong></p>
        <p>Status: <strong>${item.state}</strong></p>
      </div>
    </div>

    ${statsHTML}
  `;

  drawer.classList.add('open');
}

function closeDrawer() {
  document.getElementById('itemDetailDrawer').classList.remove('open');
}

// HISTORY LOGIC (local cache of past jobs, backed by GET /api/jobs when reachable)
function loadHistoryCache() {
  const data = localStorage.getItem('deepflow_history');
  if (data) {
    jobsHistory = JSON.parse(data);
  }
}

function saveHistoryCache() {
  localStorage.setItem('deepflow_history', JSON.stringify(jobsHistory));
}

function renderHistoryList() {
  const container = document.getElementById('jobHistoryList');
  container.innerHTML = '';

  if (jobsHistory.length === 0) {
    container.innerHTML = '<div class="no-history-msg">No jobs run yet</div>';
    return;
  }

  jobsHistory.forEach(job => {
    const card = document.createElement('div');
    card.className = `history-card ${currentJobId === job.jobId ? 'active' : ''}`;
    card.tabIndex = 0;
    card.setAttribute('role', 'button');
    card.setAttribute('aria-label', `View job ${job.jobId}, ${job.state}`);
    card.onclick = () => loadHistoryJobIntoDashboard(job.jobId);
    card.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        loadHistoryJobIntoDashboard(job.jobId);
      }
    };

    const timeStr = new Date(job.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    card.innerHTML = `
      <div class="history-card-header">
        <span class="history-id">${job.jobId.substring(0, 12)}...</span>
        <span class="history-state ${job.state.toLowerCase()}">${job.state}</span>
      </div>
      <div class="history-card-details">
        <div>Docs: ${job.itemsComplete}/${job.totalItems}</div>
        <div>Read: ${job.avgReadability.toFixed(1)}</div>
        <div>Time: ${timeStr}</div>
      </div>
    `;
    container.appendChild(card);
  });
}

function loadHistoryJobIntoDashboard(jobId) {
  const job = jobsHistory.find(j => j.jobId === jobId);
  if (!job) return;

  currentJobId = jobId;
  renderHistoryList(); // Update active indicator class

  activeJobData = {
    urls: [],
    items: Array.from({ length: job.totalItems }).map((_, idx) => ({
      index: idx,
      url: `item-${idx + 1}`,
      stage: 'DONE',
      state: idx < job.itemsComplete ? 'SUCCESS' : 'FAILED',
      error: idx >= job.itemsComplete ? 'Fetch failed' : null,
      startTime: 0,
      endTime: 0,
      readabilityScore: job.avgReadability,
      wordCount: job.itemsComplete > 0 ? Math.floor(job.totalWords / job.itemsComplete) : 0,
      wordFrequencies: job.topWords
    })),
    aggregates: {
      documentsProcessed: job.itemsComplete,
      documentsErrored: job.itemsErrored,
      averageReadability: job.avgReadability,
      totalWordsAnalyzed: job.totalWords,
      topWords: job.topWords
    }
  };

  const dashboard = document.getElementById('dashboardPanel');
  dashboard.classList.remove('disabled');

  document.getElementById('activeJobIdText').textContent = `Job Dashboard — ${jobId.substring(0, 8)}...`;

  // Set badge
  const badge = document.getElementById('jobStateBadge');
  badge.className = `status-badge ${job.state}`;
  document.getElementById('jobStateText').textContent = job.state;

  renderItemsTable();
  updateKPIs();
  updateTopWords();
  updateQueueDepth(0);
}

// LIVE BACKEND API INTEGRATION
async function runLiveJob(urls) {
  try {
    const res = await fetch(backendUrl('/api/jobs'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeader() },
      body: JSON.stringify({ urls })
    });

    if (res.status === 401) {
      handleUnauthorized();
      document.getElementById('submitBtn').disabled = false;
      document.getElementById('submitBtn').innerHTML = '<i data-lucide="play-circle"></i> Start Analysis';
      return;
    }

    if (!res.ok) {
      throw new Error(`HTTP Error: ${res.status}`);
    }

    const data = await res.json();
    const jobId = data.jobId;

    // Map validated items for skeleton
    const itemsSkeleton = urls.map(url => ({
      url,
      valid: true
    }));

    renderDashboardSkeleton(jobId, itemsSkeleton);
    connectLiveWebSocket(jobId);

  } catch (error) {
    showNotification(`Failed to submit job to server: ${error.message}`, 'error');
    document.getElementById('submitBtn').disabled = false;
    document.getElementById('submitBtn').innerHTML = '<i class="play-circle"></i> Start Analysis';
  }
}

function connectLiveWebSocket(jobId) {
  const base = (window.APP_CONFIG && window.APP_CONFIG.BACKEND_URL) || `http://${window.location.hostname}:8080`;
  const wsBase = base.replace(/^http/, 'ws');
  const token = sessionStorage.getItem('deepflow_token');
  const wsUrl = `${wsBase}/api/jobs/${jobId}/stream?token=${encodeURIComponent(token || '')}`;

  wsConnection = new WebSocket(wsUrl);

  wsConnection.onmessage = (event) => {
    const msg = JSON.parse(event.data);

    if (msg.eventType === 'item_update') {
      const item = msg.itemStatus;
      const localItem = activeJobData.items[item.index];
      if (localItem) {
        localItem.stage = item.stage;
        localItem.state = item.state;
        localItem.error = item.error;
        if (item.readabilityScore) localItem.readabilityScore = item.readabilityScore;
        if (item.wordCount) localItem.wordCount = item.wordCount;
        if (item.links) localItem.links = item.links;
      }
      renderItemsTable();
    } else if (msg.eventType === 'aggregate_update') {
      activeJobData.aggregates = msg.aggregates;
      updateKPIs();
      updateTopWords();
    } else if (msg.eventType === 'queue_depth') {
      updateQueueDepth(msg.queueDepth);
    } else if (msg.eventType === 'job_complete') {
      if (msg.aggregates) {
        activeJobData.aggregates = msg.aggregates;
        updateKPIs();
        updateTopWords();
      }
      const badge = document.getElementById('jobStateBadge');
      badge.className = 'status-badge COMPLETED';
      document.getElementById('jobStateText').textContent = 'COMPLETED';

      document.getElementById('submitBtn').disabled = false;
      document.getElementById('submitBtn').innerHTML = '<i data-lucide="play-circle"></i> Start Analysis';
      lucide.createIcons();

      // Add to history
      const agg = activeJobData.aggregates;
      jobsHistory.unshift({
        jobId: currentJobId,
        state: 'COMPLETED',
        totalItems: activeJobData.items.length,
        itemsComplete: agg.documentsProcessed || 0,
        itemsErrored: agg.documentsErrored || 0,
        avgReadability: agg.averageReadability || 0,
        totalWords: agg.totalWordsAnalyzed || 0,
        topWords: agg.topWords || {},
        createdAt: Date.now()
      });
      saveHistoryCache();
      renderHistoryList();

      showNotification('Analysis completed on Live Server!', 'success');
      wsConnection.close();
    }
  };

  wsConnection.onclose = (event) => {
    if (event.code === 1008 || event.code === 4401) {
      handleUnauthorized();
    }
  };

  wsConnection.onerror = (err) => {
    showNotification('Live Server WebSocket encountered an error.', 'error');
  };
}

// Utility to escape HTML
function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
}
