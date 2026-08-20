"""
orchestrator.py
----------------
Drives the actual self-correction loop end-to-end: retrieve -> score ->
decide() -> act. This is the file that USES state_machine.decide() in
a loop, calling real subsystems (retrieval, query rewriting) that
decide() itself deliberately never touches.
"""
from dataclasses import dataclass, field
from app.correction.models import CorrectionState
from app.correction.query_rewriter import rewrite_query
from app.correction.scoring import compute_context_quality_score
from app.correction.state_machine import decide
from app.retrieval import sparse_search
from app.retrieval.pipeline import retrieve
from app.retrieval.reranker import rerank


REFUSAL_MESSAGE = "Insufficient local context found to answer safely."

@dataclass
class OrchestratorResult:
    chunks: list[dict]
    context_quality_score: float
    self_correction_triggered: bool
    retry_count: int
    refused: bool
    refusal_reason: str = ""
    trace: list[str] = field(default_factory=list)

def _retry_retrieval(query: str) -> list[dict]:
    """
        FR-2.4 retry step: rewrite the query into 3 variants, search each
        with BM25 (the "switch search mode Dense -> BM25" part of the spec),
        merge + dedupe the results, then rerank the merged pool against the
        ORIGINAL query (relevance is judged against what the user actually
        asked, not the rewritten variants).
    """
    rewritten_queries = rewrite_query(query)

    seen_keys = set()
    merged_candidates = []

    for rewritten_query in rewritten_queries:
        results = sparse_search.search(rewritten_query, top_k=20)
        for chunk in results:
            key = (chunk["source_doc"], chunk["chunk_index"])
            if key not in seen_keys:
                seen_keys.add(key)
                merged_candidates.append(chunk)

    return rerank(query, merged_candidates, top_k=5)

def run_self_correction(query: str) -> OrchestratorResult:
    """
        Full self-correction loop for a single query. Returns an
        OrchestratorResult describing what happened and, if not refused,
        the final chunks to hand off to generation.
    """
    trace: list[str] = []
    retry_count = 0

    chunks = retrieve(query)
    score = compute_context_quality_score(chunks)
    trace.append(f"Initial retrieval: score={score:.3f}")

    decision = decide(score, retry_count)
    trace.append(f"Decision: {decision.state.value} - {decision.reason}")

    while decision.state == CorrectionState.RETRY:
        retry_count += 1
        chunks = _retry_retrieval(query)
        score = compute_context_quality_score(chunks)
        trace.append(f"Retry {retry_count}: score={score:.3f}")

        decision = decide(score, retry_count)
        trace.append(f"Decision: {decision.state.value} - {decision.reason}")

    if decision.state == CorrectionState.REFUSED:
        return OrchestratorResult(
            chunks=[],
            context_quality_score=score,
            self_correction_triggered=(retry_count > 0),
            retry_count=retry_count,
            refused=True,
            refusal_reason=decision.reason,
            trace=trace,
        )

    return OrchestratorResult(
        chunks=chunks,
        context_quality_score=score,
        self_correction_triggered=(retry_count > 0),
        retry_count=retry_count,
        refused=False,
        trace=trace,
    )

