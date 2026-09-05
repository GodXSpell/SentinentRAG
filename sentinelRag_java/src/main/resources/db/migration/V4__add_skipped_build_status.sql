-- V4__add_skipped_build_status.sql
-- Adds an explicit CHECK constraint permitting the new SKIPPED status (see
-- BuildStatus.java javadoc for why SKIPPED exists — a suite with zero test cases has
-- nothing for the quality gate to evaluate, so it gets its own honest status rather
-- than being forced into a misleading PASSED or FAILED).
--
-- V1 never had a real CHECK constraint on status (only a descriptive SQL comment), so
-- there is no existing constraint to drop first — this simply adds one that didn't
-- exist before, covering all four valid values.

ALTER TABLE ci_build_runs
    ADD CONSTRAINT ci_build_runs_status_check
    CHECK (status IN ('PASSED', 'FAILED', 'RUNNING', 'SKIPPED'));