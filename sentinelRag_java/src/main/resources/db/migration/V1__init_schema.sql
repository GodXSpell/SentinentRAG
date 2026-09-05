-- V1__init_schema.sql
-- Sentinel-RAG control plane initial schema.
-- Run order matters for FKs: test_suites before test_cases.

-- Needed for gen_random_uuid(). Safe to run even if already enabled elsewhere.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Golden test suites (a named collection of test cases)
CREATE TABLE test_suites (
    suite_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Individual test items in a golden dataset
CREATE TABLE test_cases (
    test_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id              UUID REFERENCES test_suites(suite_id) ON DELETE CASCADE,
    query                 TEXT NOT NULL,
    expected_ground_truth TEXT NOT NULL,
    tags                  VARCHAR(255)[],
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Semantic evaluation cache (key to sub-15s CI runs).
-- cache_hash is a SHA256 hex digest of (PromptTemplate + ConfigParams + Query + ContextChunk) --
-- a natural key by design (FR-1.3), so it stays VARCHAR, not UUID.
CREATE TABLE eval_cache (
    cache_hash               VARCHAR(64) PRIMARY KEY,
    generated_answer         TEXT NOT NULL,
    faithfulness_score       FLOAT NOT NULL,
    context_precision_score  FLOAT NOT NULL,
    answer_relevancy_score   FLOAT NOT NULL,
    evaluator_model          VARCHAR(100) NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- CI build run history
CREATE TABLE ci_build_runs (
    build_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commit_sha            VARCHAR(40) NOT NULL,
    branch_name           VARCHAR(100) NOT NULL,
    pull_request_id       INT,
    status                VARCHAR(20) NOT NULL, -- 'PASSED', 'FAILED', 'RUNNING', 'SKIPPED'
    mean_faithfulness     FLOAT NOT NULL,
    mean_precision        FLOAT NOT NULL,
    cache_hit_rate        FLOAT NOT NULL,
    execution_duration_ms BIGINT NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);