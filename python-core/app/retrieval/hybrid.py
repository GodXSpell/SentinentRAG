"""
hybrid.py
---------
Combines dense (Qdrant) and sparse (BM25) search results using
Reciprocal Rank Fusion (RRF) — a simple, score-scale-agnostic way
to merge two differently-scored ranked lists into one.
"""

from typing import Any

from app.retrieval import dense_search, sparse_search
from app.ingestion.embedder import embed_text

RRF_K = 60

def _chunk_key(chunk: dict) -> tuple:
    # Uniquely identifies a chunk across both result lists, so we can
    # match "the same chunk" even though dense/sparse return separate
    # dict instances.
    return (chunk["source_doc"], chunk["chunk_index"])

def hybrid_search(
    query: str,
    dense_top_k: int,
    sparse_top_k: int,
    collection_name: str | None = None,
    bm25_index: Any | None = None,
    bm25_chunks: list[dict] | None = None,
) -> list[dict]:
    query_vector = embed_text(query)

    dense_results = dense_search.search(
        query_vector, top_k=dense_top_k, collection_name=collection_name
    )
    sparse_results = sparse_search.search(
        query, top_k=sparse_top_k, bm25_index=bm25_index, bm25_chunks=bm25_chunks
    )

    rrf_scores: dict[tuple, float] = {}
    chunk_lookup: dict[tuple, dict] = {}

    for rank, chunk in enumerate(dense_results):
        key = _chunk_key(chunk)
        rrf_scores[key] = rrf_scores.get(key, 0.0) + 1.0 / (RRF_K + rank + 1)
        chunk_lookup[key] = chunk

    for rank, chunk in enumerate(sparse_results):
        key = _chunk_key(chunk)
        rrf_scores[key] = rrf_scores.get(key, 0.0) + 1.0 / (RRF_K + rank + 1)
        chunk_lookup[key] = chunk

    ranked_keys = sorted(rrf_scores.keys(), key=lambda k: rrf_scores[k], reverse=True)

    return [
        {**chunk_lookup[key], "rrf_score": rrf_scores[key]}
        for key in ranked_keys
    ]