-- V8__add_delivery_id_idempotency.sql
-- Adds delivery_id column for GitHub webhook delivery idempotency.
-- Nullable to avoid breaking existing historical rows, with unique partial index.

ALTER TABLE ci_build_runs ADD COLUMN delivery_id VARCHAR(72) NULL;

CREATE UNIQUE INDEX uq_ci_build_run_delivery_id ON ci_build_runs (delivery_id) WHERE delivery_id IS NOT NULL;

