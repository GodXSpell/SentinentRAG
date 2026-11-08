-- V6__add_refusal_and_cache_execution_fields.sql
-- Adds refusal_accuracy to ci_build_runs, and persists refused/retry metadata
-- to eval_cache so that cache hits retain refusal calculation data (Plan §3.1).

ALTER TABLE ci_build_runs ADD COLUMN refusal_accuracy FLOAT NULL;

ALTER TABLE eval_cache
    ADD COLUMN refused BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN self_correction_triggered BOOLEAN NOT NULL DEFAULT FALSE;

