-- Job master table
CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    state TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Job items (per-URL status)
CREATE TABLE IF NOT EXISTS job_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id TEXT NOT NULL,
    url TEXT NOT NULL,
    item_index INTEGER NOT NULL,
    stage TEXT,
    state TEXT,
    error TEXT,
    started_at INTEGER,
    ended_at INTEGER,
    FOREIGN KEY(job_id) REFERENCES jobs(id),
    UNIQUE(job_id, item_index)
);

-- Job results (content + analysis)
CREATE TABLE IF NOT EXISTS job_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id TEXT NOT NULL,
    url TEXT NOT NULL,
    content TEXT,
    links TEXT,
    word_freq TEXT,
    readability_score REAL,
    FOREIGN KEY(job_id) REFERENCES jobs(id)
);

-- Job aggregates (running totals)
CREATE TABLE IF NOT EXISTS job_aggregates (
    job_id TEXT PRIMARY KEY,
    documents_processed INTEGER DEFAULT 0,
    documents_errored INTEGER DEFAULT 0,
    average_readability REAL DEFAULT 0,
    total_words_analyzed INTEGER DEFAULT 0,
    top_words TEXT,
    last_updated INTEGER,
    FOREIGN KEY(job_id) REFERENCES jobs(id)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_job_items_job_id ON job_items(job_id);
CREATE INDEX IF NOT EXISTS idx_job_results_job_id ON job_results(job_id);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs(created_at);
