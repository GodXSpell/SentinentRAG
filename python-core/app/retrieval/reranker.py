"""
reranker.py
-----------
Cross-encoder reranking: takes the hybrid search results and re-scores
each (query, chunk) pair directly with a small transformer model,
which is more accurate than the dense/sparse similarity scores alone.

Uses sentence-transformers' CrossEncoder (a thin wrapper around a
HuggingFace sequence-classification model) rather than a manually
exported ONNX model — simpler and just as fast for portfolio scale.
If you want the ONNX/INT8 optimization later, this is the one function
(`_get_model`) you'd swap out; nothing else in the app needs to change.
"""

from sentence_transformers import CrossEncoder

RERANKER_MODEL_NAME = "cross-encoder/ms-marco-MiniLM-L-6-v2"

_model: CrossEncoder | None = None

def _get_model():
    global _model
    if _model is None:
        _model = CrossEncoder(RERANKER_MODEL_NAME)
    return _model

def rerank(query: str, candidates: list[dict], top_k: int) -> list[dict]:
    """
        Re-score `candidates` (chunks from hybrid search) against `query`
        using the cross-encoder, and return the top_k, sorted by the new
        rerank_score (highest first).
    """
    if not candidates:
        return []

    model = _get_model()

    pairs = [(query, c["text"]) for c in candidates]
    scores = model.predict(pairs)

    for candidate, score in zip(candidates, scores):
        candidate["rerank_score"] = score

    ranked = sorted(candidates, key=lambda c: c["rerank_score"], reverse=True)
    return ranked[:top_k]