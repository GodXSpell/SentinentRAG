"""
reranker.py
-----------
Cross-encoder reranking: takes the hybrid search results and re-scores
each (query, chunk) pair directly with a small transformer model,
which is more accurate than the dense/sparse similarity scores alone.

Uses sentence-transformers' CrossEncoder (a thin wrapper around a
HuggingFace sequence-classification model) rather than a manually
exported ONNX model - simpler and just as fast for portfolio scale.
"""

from sentence_transformers import CrossEncoder

RERANKER_MODEL_NAME = "cross-encoder/ms-marco-MiniLM-L-6-v2"

_model: CrossEncoder | None = None


def _get_model() -> CrossEncoder:
    global _model
    if _model is None:
        _model = CrossEncoder(RERANKER_MODEL_NAME)
    return _model


def rerank(query: str, candidates: list[dict], top_k: int) -> list[dict]:
    """
        Re-score `candidates` against a single `query` using the
        cross-encoder, and return the top_k, sorted by rerank_score
        (highest first).
    """
    if not candidates:
        return []

    model = _get_model()
    pairs = [(query, c["text"]) for c in candidates]
    scores = model.predict(pairs)

    for candidate, score in zip(candidates, scores):
        candidate["rerank_score"] = float(score)

    ranked = sorted(candidates, key=lambda c: c["rerank_score"], reverse=True)
    return ranked[:top_k]


def rerank_multi_query(
    queries: list[str], candidates: list[dict], top_k: int
) -> list[dict]:
    """
        Re-score `candidates` against MULTIPLE queries (e.g. the original
        query plus its rewritten variants), and rank by the MEAN score
        across all queries.

        Why mean, not max: a chunk that scores well against every phrasing
        of the question is a more robust, genuine match than a chunk that
        only happens to score well against one lucky rewrite. Mean rewards
        consistent relevance across phrasings rather than a single
        coincidental vocabulary overlap.

        This exists specifically to fix a failure mode where reranking a
        retry pool against only the ORIGINAL query discounted genuinely
        better candidates that a rewritten query found via BM25 but whose
        wording didn't match the original query's vocabulary.
    """
    if not candidates:
        return []
    if not queries:
        raise ValueError("rerank_multi_query requires at least one query")

    model = _get_model()

    # Build all (query, chunk_text) pairs across every query, in one
    # batch, so the model only needs one predict() call - not one per
    # query. len(pairs) == len(queries) * len(candidates).
    pairs = [
        (query, candidate["text"])
        for query in queries
        for candidate in candidates
    ]
    scores = model.predict(pairs)

    # scores is a flat list in the same order as `pairs`: all scores
    # for query[0] first, then all scores for query[1], etc. Reshape
    # back into per-candidate score lists.
    num_candidates = len(candidates)
    per_candidate_scores: list[list[float]] = [[] for _ in range(num_candidates)]

    for query_index in range(len(queries)):
        start = query_index * num_candidates
        query_scores = scores[start : start + num_candidates]
        for candidate_index, score in enumerate(query_scores):
            per_candidate_scores[candidate_index].append(float(score))

    for candidate, score_list in zip(candidates, per_candidate_scores):
        candidate["rerank_score"] = sum(score_list) / len(score_list)

    ranked = sorted(candidates, key=lambda c: c["rerank_score"], reverse=True)
    return ranked[:top_k]