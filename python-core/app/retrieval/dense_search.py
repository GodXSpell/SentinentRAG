"""
dense_search.py
----------------
Thin wrapper around the Qdrant client. Handles collection setup,
upserting embedded chunks, and dense (vector similarity) search.
"""

import uuid

from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct

from app.config import settings
from app.ingestion.chunker import Chunk

_client: QdrantClient | None = None

def _get_client() -> QdrantClient:
    global _client
    if _client is None:
        _client = QdrantClient(url=settings.qdrant_url)
    return _client

def ensure_collection() -> None:
    """
        Create the collection if it doesn't already exist. Safe to call
        on every app startup — it's a no-op if the collection is already there.
    """
    client = _get_client()
    existing = [c.name for c in client.get_collections().collections]

    if settings.qdrant_collection in existing:
        return

    client.create_collection(
        collection_name=settings.qdrant_collection,
        vectors_config=VectorParams(
            size=settings.embedding_dim,
            distance=Distance.COSINE
        ),
    )

def upsert_chunks(chunks: list[Chunk], vectors: list[list[float]]) -> None:
    """
        Store embedded chunks in Qdrant. `chunks` and `vectors` must be
        the same length and in the same order — vectors[i] is the
        embedding of chunks[i].
    """
    assert len(chunks) == len(vectors), (
        f"chunks and vectors must be the same length, got {len(chunks)} chunks and {len(vectors)} vectors"
    )

    client = _get_client()
    points = [
        PointStruct(
            id=str(uuid.uuid4()),
            vector=vector,
            payload={
                "text": chunks.text,
                "source_doc": chunks.source_doc,
                "section_title": chunks.section_title,
                "chunk_index": chunks.chunk_index,
            }
        )
        for chunks, vector in zip(chunks, vectors)
    ]

    client.upsert(
        collection_name=settings.qdrant_collection,
        points=points,
    )

def search(query_vector: list[float], top_k: int) -> list[dict]:
    """
        Find the top_k chunks whose embeddings are closest to query_vector.

        Returns a list of dicts, each containing the stored payload fields
        plus a "score" (higher = more similar, since we use cosine distance).
    """
    client = _get_client()
    results = client.query_points(
        collection_name=settings.qdrant_collection,
        query=query_vector,
        limit=top_k,
    ).points

    return [
        {
            "text": hit.payload["text"],
            "source_doc": hit.payload["source_doc"],
            "section_title": hit.payload["section_title"],
            "chunk_index": hit.payload["chunk_index"],
            "score": 1 - hit.score,  # convert cosine distance to similarity
        }
        for hit in results
    ]
