-- Job master table
CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    state TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- Job items (per-URL status)
CREATE TABLE IF NOT EXISTS job_items (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id TEXT NOT NULL,
    url TEXT NOT NULL,
    item_index INTEGER NOT NULL,
    stage TEXT,
    state TEXT,
    error TEXT,
    started_at BIGINT,
    ended_at BIGINT,
    FOREIGN KEY(job_id) REFERENCES jobs(id),
    UNIQUE(job_id, item_index)
);

-- Job results (content + analysis)
CREATE TABLE IF NOT EXISTS job_results (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id TEXT NOT NULL,
    url TEXT NOT NULL,
    content TEXT,
    links TEXT,
    word_freq TEXT,
    readability_score DOUBLE PRECISION,
    FOREIGN KEY(job_id) REFERENCES jobs(id)
);

-- Job aggregates (running totals)
CREATE TABLE IF NOT EXISTS job_aggregates (
    job_id TEXT PRIMARY KEY,
    documents_processed INTEGER DEFAULT 0,
    documents_errored INTEGER DEFAULT 0,
    average_readability DOUBLE PRECISION DEFAULT 0,
    total_words_analyzed INTEGER DEFAULT 0,
    top_words TEXT,
    last_updated BIGINT,
    FOREIGN KEY(job_id) REFERENCES jobs(id)
);

-- Users (auth). Single-tenant tool: no roles/permissions column.
CREATE TABLE IF NOT EXISTS users (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at BIGINT NOT NULL
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_job_items_job_id ON job_items(job_id);
CREATE INDEX IF NOT EXISTS idx_job_results_job_id ON job_results(job_id);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs(created_at);
