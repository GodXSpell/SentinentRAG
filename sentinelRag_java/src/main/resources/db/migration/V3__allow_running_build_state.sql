-- V3__allow_running_build_state.sql
-- Enables inserting a ci_build_runs row immediately on webhook receipt, with status
-- RUNNING, before evaluation has produced any scores. Without this, there's no row to
-- attach a GitHub "pending" check-run status to, and a crashed/timed-out build leaves
-- zero trace in the table. The row is later UPDATEd in place once evaluation finishes,
-- flipping status to PASSED/FAILED and filling in the real values.

ALTER TABLE ci_build_runs
    ALTER COLUMN mean_faithfulness      DROP NOT NULL,
    ALTER COLUMN mean_precision         DROP NOT NULL,
    ALTER COLUMN cache_hit_rate         DROP NOT NULL,
    ALTER COLUMN execution_duration_ms  DROP NOT NULL;