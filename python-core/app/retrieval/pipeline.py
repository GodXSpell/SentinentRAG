"""
pipeline.py
-----------
Ties hybrid search and reranking together into a single retrieval
call: hybrid_search (dense + sparse, RRF-fused) -> rerank -> final top-K.
"""

from __future__ import annotations
from typing import Any

from app.config import settings
from app.retrieval.hybrid import hybrid_search
from app.retrieval.reranker import rerank
from app.evaluation.pipeline_config import PipelineConfig


def retrieve(
    query: str,
    config: PipelineConfig | None = None,
    bm25_index: Any | None = None,
    bm25_chunks: list[dict] | None = None,
) -> list[dict]:
    """
    Executes hybrid retrieval and cross-encoder reranking.
    Uses config fields if provided; otherwise falls back to global settings.
    """
    if config is not None:
        dense_top_k = config.retrieval_top_k
        sparse_top_k = config.retrieval_top_k
        rerank_top_k = config.rerank_top_k
        collection_name = config.collection_name
    else:
        dense_top_k = settings.dense_top_k
        sparse_top_k = settings.sparse_top_k
        rerank_top_k = settings.rerank_top_k
        collection_name = settings.qdrant_collection

    candidates = hybrid_search(
        query,
        dense_top_k=dense_top_k,
        sparse_top_k=sparse_top_k,
        collection_name=collection_name,
        bm25_index=bm25_index,
        bm25_chunks=bm25_chunks,
    )
    return rerank(query, candidates, rerank_top_k)
