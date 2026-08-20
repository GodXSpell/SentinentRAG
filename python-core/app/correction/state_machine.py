"""
state_machine.py
-----------------
Given a context quality score and how many retries have already
happened, decide what should happen next.

This module makes NO network calls and touches NO other subsystem
(no Qdrant, no LLM calls) — it's a pure decision function, deliberately
kept that way so it's trivially unit-testable: given (score, retry_count),
what CorrectionDecision comes out?

The actual orchestration (calling retrieval, calling the query rewriter,
calling generation) lives in a separate file (orchestrator.py) that USES
this decision function in a loop.
"""

from app.correction.models import (
    DIRECT_THRESHOLD,
    MAX_RETRIES,
    RETRY_FLOOR,
    CorrectionDecision,
    CorrectionState,
)


def decide(score: float, retry_count: int) -> CorrectionDecision:
    """
    Given the current context_quality_score and how many retries have
    already been used, decide the next state.

    Rules (FR-2.4, with the "same bar applies after retry" decision
    we locked in — no softened threshold for retries, since faithfulness
    is a hard reliability commitment):
      - score >= DIRECT_THRESHOLD -> DIRECT, always, regardless of retry_count.
      - score < RETRY_FLOOR -> REFUSED immediately. A very bad score never
        earns a retry, even on the first attempt.
      - Otherwise (the middle band, RETRY_FLOOR <= score < DIRECT_THRESHOLD):
          - retry_count < MAX_RETRIES -> RETRY.
          - retry_count >= MAX_RETRIES -> REFUSED (retry budget exhausted).
    """
    if score >= DIRECT_THRESHOLD:
        return CorrectionDecision(
            state=CorrectionState.DIRECT,
            reason=f"Score {score:.2f} >= DIRECT_THRESHOLD {DIRECT_THRESHOLD:.2f}, proceeding directly.",
        )

    if score < RETRY_FLOOR:
        return CorrectionDecision(
            state=CorrectionState.REFUSED,
            reason=f"Score {score:.2f} < RETRY_FLOOR {RETRY_FLOOR:.2f}, refusing outright.",
        )

    # Middle band: RETRY_FLOOR <= score < DIRECT_THRESHOLD
    if retry_count < MAX_RETRIES:
        return CorrectionDecision(
            state=CorrectionState.RETRY,
            reason=(
                f"Score {score:.2f} in middle band, "
                f"retry_count {retry_count} < MAX_RETRIES {MAX_RETRIES}, retrying."
            ),
        )

    # Middle band but retry budget exhausted
    return CorrectionDecision(
        state=CorrectionState.REFUSED,
        reason=(
            f"Score {score:.2f} in middle band, but retry_count {retry_count} "
            f">= MAX_RETRIES {MAX_RETRIES}, refusing after retry."
        ),
    )