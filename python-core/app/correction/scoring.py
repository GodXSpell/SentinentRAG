"""
scoring.py
----------
Turns a list of reranked chunks into a single context_quality_score
in the 0.0-1.0 range, which is what the state machine's thresholds
(DIRECT_THRESHOLD, RETRY_FLOOR) assume.

We use the top-ranked chunk's raw rerank_score (an unbounded real
number, e.g. -5 to 7 from the cross-encoder) and squash it through
a sigmoid: sigmoid(x) = 1 / (1 + e^-x).
"""

import math

def compute_context_quality_score(reranked_chunks: list[dict]) -> float:
    """
        reranked_chunks: output of reranker.rerank() / pipeline.retrieve(),
        each dict expected to have a "rerank_score" key. Must be sorted
        with the best match first (pipeline.retrieve() already guarantees this).

        Returns 0.0 if given an empty list (no context at all is the
        worst possible case - always REFUSED-worthy).
    """
    if not reranked_chunks:
        return 0.0

    top_score = reranked_chunks[0]["rerank_score"]
    return 1 / (1 + math.exp(-top_score))