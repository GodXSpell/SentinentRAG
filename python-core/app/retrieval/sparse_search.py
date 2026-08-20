"""
sparse_search.py
-----------------
BM25 keyword search over the same chunk corpus as Qdrant.
Kept in-memory: rebuilt from Qdrant's stored payloads on demand,
since our corpus is small (portfolio scale, not production scale).
"""

from rank_bm25 import BM25Okapi

from app.config import settings
from app.retrieval.dense_search import _get_client

_bm25_index: BM25Okapi | None = None
_bm25_chunks: list[dict] | None = None

def _tokenize(text: str) -> list[str]:
    return [token.lower() for token in text.split()]

def build_index() -> None:
    """
        Pull every stored chunk out of Qdrant and build a BM25 index over
        them. Call this after ingestion, before sparse search is used.
    """
    global _bm25_index, _bm25_chunks

    client = _get_client()
    all_points = []
    offset = None

    while True:
        points, offset = client.scroll (
            collection_name=settings.qdrant_collection,
            limit=256,
            offset=offset,
            with_payload=True,
            with_vectors=False
        )

        all_points.extend(points)
        if offset is None:
            break

    _bm25_chunks = [point.payload for point in all_points]
    tokenized = [ _tokenize(chunk["text"]) for chunk in _bm25_chunks]
    _bm25_index = BM25Okapi(tokenized)


def search(query: str, top_k: int = settings.sparse_top_k) -> list[dict]:
    """
        Return the top_k chunks by BM25 score for the given query text.
        Call build_index() at least once before this (e.g. at startup,
        or right after ingestion).
    """
    if _bm25_index is None or _bm25_chunks is None:
        build_index()

    tokenized_query = _tokenize(query)
    scores = _bm25_index.get_scores(tokenized_query)

    ranked = sorted(
        zip(_bm25_chunks, scores),
        key=lambda pair: pair[1],
        reverse=True
    )[:top_k]

    return [
        {
            "text": chunk["text"],
            "source_doc": chunk["source_doc"],
            "section_title": chunk["section_title"],
            "chunk_index": chunk["chunk_index"],
            "score": score
        }
        for chunk, score in ranked
    ]