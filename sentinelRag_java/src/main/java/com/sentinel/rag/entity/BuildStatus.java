package com.sentinel.rag.entity;

/**
 * Status of a CI evaluation build run.
 * PASSED: All quality gates passed.
 * FAILED: One or more quality gates breached.
 * RUNNING: Evaluation in progress.
 * SKIPPED: Test suite was empty (no cases to evaluate).
 * ERROR: Unrecoverable engine error, timeout, or crash recovery sweep.
 */
public enum BuildStatus {
    RUNNING,
    PASSED,
    FAILED,
    SKIPPED,
    ERROR
}
