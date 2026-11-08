-- V7__add_error_build_status.sql
-- Expands ci_build_runs status CHECK constraint to permit ERROR status
-- for unrecoverable crash/timeout/gRPC exceptions and startup crash sweeps.

ALTER TABLE ci_build_runs DROP CONSTRAINT ci_build_runs_status_check;

ALTER TABLE ci_build_runs
    ADD CONSTRAINT ci_build_runs_status_check
    CHECK (status IN ('PASSED', 'FAILED', 'RUNNING', 'SKIPPED', 'ERROR'));

