-- V2__add_test_result_records.sql
-- Per-test-case results for a CI build run. Needed because ci_build_runs only stores
-- aggregate mean scores (FR-1.4/1.5) — without this table, the PR comment bot can report
-- "mean faithfulness dropped 0.04" but never "question #7 specifically regressed."
-- id is a plain auto-increment bigint: this table has no natural key, is never referenced
-- by another table as a parent, and is purely additive/append-only per build run, so a
-- synthetic UUID buys nothing here that a bigserial doesn't already give more cheaply.

CREATE TABLE test_result_records (
    id                       BIGSERIAL PRIMARY KEY,
    build_id                 UUID NOT NULL REFERENCES ci_build_runs(build_id) ON DELETE CASCADE,
    test_id                  UUID NOT NULL,  -- intentionally not a FK: test_cases can be
                                              -- edited/deleted independently of historical
                                              -- build results; we want the historical
                                              -- record to survive even if the test case
                                              -- it referenced is later removed
    faithfulness_score       FLOAT NOT NULL,
    context_precision_score  FLOAT NOT NULL,
    answer_relevancy_score   FLOAT NOT NULL,
    execution_time_ms        BIGINT NOT NULL,
    was_cached               BOOLEAN NOT NULL DEFAULT FALSE,
    generated_answer         TEXT NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Every query that matters here is "give me all results for build X" — index it.
CREATE INDEX idx_test_result_records_build_id ON test_result_records(build_id);