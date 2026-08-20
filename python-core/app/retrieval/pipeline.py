"""
pipeline.py
-----------
Ties hybrid search and reranking together into a single retrieval
call: hybrid_search (dense + sparse, RRF-fused) -> rerank -> final top-K.
"""

from app.retrieval.hybrid import hybrid_search
from app.retrieval.reranker import rerank
from app.config import settings

def retrieve(query: str) -> list[dict]:
    candidates = hybrid_search(
        query,
        dense_top_k=settings.dense_top_k,
        sparse_top_k=settings.sparse_top_k
    )
    return rerank(query, candidates, settings.rerank_top_k)
