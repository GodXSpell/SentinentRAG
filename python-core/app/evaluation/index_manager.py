"""
index_manager.py
----------------
Manages Qdrant collections and in-memory BM25 indices per index_hash.
Protects against partial/half-built indices using Qdrant aliases:
ingests into an isolated temporary collection, and atomically points the alias
`sentinel_{index_hash[:12]}` only upon complete, successful ingestion.
"""

from __future__ import annotations
import asyncio
from dataclasses import dataclass
import logging
from pathlib import Path
import time
import uuid
from typing import Any

from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance,
    VectorParams,
    PointStruct,
    CreateAliasOperation,
    CreateAlias,
)
from rank_bm25 import BM25Okapi

from app.config import settings, PROJECT_ROOT
from app.evaluation.pipeline_config import PipelineConfig
from app.evaluation.corpus_fetcher import CorpusFetcher
from app.ingestion.chunker import chunk_document, Chunk
from app.ingestion.embedder import embed_batch

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class IndexReadyResult:
    """Outcome of ensuring an index is ready for evaluation."""
    collection_name: str
    was_created: bool
    duration_ms: int


class IndexManager:
    """
    Coordinates collection lifecycle, corpus ingestion, embedding, and BM25 index building.
    Thread-safe and async-safe via per-instance per-hash locks.
    """

    def __init__(
        self,
        client: QdrantClient | None = None,
        corpus_fetcher: CorpusFetcher | None = None,
        corpus_base_dir: Path | None = None,
    ) -> None:
        self._client = client or QdrantClient(url=settings.qdrant_url)
        self._corpus_fetcher = corpus_fetcher or CorpusFetcher()
        self._corpus_base_dir = corpus_base_dir or (PROJECT_ROOT / "data" / "corpus_cache")
        # Per-instance lock dictionary (never module-level mutable dict)
        self._locks: dict[str, asyncio.Lock] = {}
        # Per-instance BM25 store: index_hash -> (BM25Okapi, list of chunk payloads)
        self._bm25_indices: dict[str, tuple[BM25Okapi, list[dict]]] = {}

    def get_bm25(self, index_hash: str) -> tuple[BM25Okapi, list[dict]] | None:
        """Returns the in-memory BM25 index and chunk list for a given index_hash."""
        return self._bm25_indices.get(index_hash)

    async def ensure_collection(self, config: PipelineConfig) -> IndexReadyResult:
        """
        Public entry point to ensure collection is ready.
        Shields the internal ingestion task so that a caller deadline/cancellation
        does not corrupt or prematurely abort background indexing work.
        """
        return await asyncio.shield(self._ensure_collection_guarded(config))

    async def _ensure_collection_guarded(self, config: PipelineConfig) -> IndexReadyResult:
        lock = self._locks.setdefault(config.index_hash, asyncio.Lock())
        async with lock:
            return await self._ensure_collection_locked(config)

    async def _ensure_collection_locked(self, config: PipelineConfig) -> IndexReadyResult:
        alias_name = config.collection_name
        loop = asyncio.get_running_loop()

        # Check if alias exists in Qdrant
        alias_exists = await loop.run_in_executor(None, self._check_alias_exists, alias_name)
        if alias_exists:
            # If alias exists but BM25 is not loaded in memory (e.g. after service restart),
            # rebuild BM25 from stored Qdrant payloads.
            if config.index_hash not in self._bm25_indices:
                logger.info("Alias %s exists; hydrating in-memory BM25 index from Qdrant", alias_name)
                await loop.run_in_executor(None, self._hydrate_bm25_from_qdrant, alias_name, config.index_hash)

            return IndexReadyResult(
                collection_name=alias_name,
                was_created=False,
                duration_ms=0,
            )

        start_time = time.perf_counter()
        temp_col_name = f"sentinel_{config.index_hash[:12]}_{uuid.uuid4().hex[:8]}"
        logger.info("Creating new index for index_hash=%s under temporary collection %s", config.index_hash, temp_col_name)

        try:
            # Step 1: Ensure corpus files are available on disk
            corpus_dir = self._corpus_base_dir / config.corpus_version
            corpus_files: list[Path] = []
            if corpus_dir.exists() and any(corpus_dir.iterdir()):
                corpus_files = [p for p in corpus_dir.iterdir() if p.is_file()]
            elif config.corpus_ref is not None:
                corpus_files = await self._corpus_fetcher.fetch(config.corpus_ref, corpus_dir)
            else:
                # Fallback to local corpus if available
                local_dir = PROJECT_ROOT / "docs" / "corpus"
                if local_dir.exists():
                    corpus_files = [p for p in local_dir.iterdir() if p.is_file()]

            if not corpus_files:
                raise ValueError(f"No corpus documents available for corpus_version={config.corpus_version}")

            # Step 2: Chunk documents
            chunks = await loop.run_in_executor(None, self._chunk_corpus, corpus_files, config)

            # Step 3: Create temporary Qdrant collection and upload embedded points
            await loop.run_in_executor(None, self._create_and_populate_collection, temp_col_name, chunks)

            # Step 4: Build BM25 index over chunk payloads
            bm25_index, bm25_chunks = await loop.run_in_executor(None, self._build_bm25, chunks)
            self._bm25_indices[config.index_hash] = (bm25_index, bm25_chunks)

            # Step 5: Atomically publish alias pointing to temp collection
            await loop.run_in_executor(None, self._publish_alias, temp_col_name, alias_name)

            duration_ms = int((time.perf_counter() - start_time) * 1000)
            logger.info("Successfully ready index alias %s in %d ms", alias_name, duration_ms)
            return IndexReadyResult(
                collection_name=alias_name,
                was_created=True,
                duration_ms=duration_ms,
            )

        except Exception as e:
            logger.error("Failed building index for %s: %s", temp_col_name, e, exc_info=True)
            # Cleanup temporary collection on error to prevent partial index leaks
            try:
                await loop.run_in_executor(None, self._delete_collection_if_exists, temp_col_name)
            except Exception:
                pass
            raise

    def _check_alias_exists(self, alias_name: str) -> bool:
        try:
            aliases = self._client.get_aliases().aliases
            return any(a.alias_name == alias_name for a in aliases)
        except Exception:
            return False

    def _chunk_corpus(self, files: list[Path], config: PipelineConfig) -> list[Chunk]:
        all_chunks: list[Chunk] = []
        for file_path in files:
            content = file_path.read_text(encoding="utf-8", errors="ignore")
            chunks = chunk_document(
                raw_text=content,
                source_doc=file_path.name,
                max_tokens=config.chunk_size_tokens,
                overlap_tokens=config.chunk_overlap_tokens,
            )
            all_chunks.extend(chunks)
        return all_chunks

    def _create_and_populate_collection(self, col_name: str, chunks: list[Chunk]) -> None:
        self._client.create_collection(
            collection_name=col_name,
            vectors_config=VectorParams(
                size=settings.embedding_dim,
                distance=Distance.COSINE,
            ),
        )

        texts = [chunk.text for chunk in chunks]
        vectors = embed_batch(texts)

        points = [
            PointStruct(
                id=str(uuid.uuid4()),
                vector=vec,
                payload={
                    "text": chunk.text,
                    "source_doc": chunk.source_doc,
                    "section_title": chunk.section_title,
                    "chunk_index": chunk.chunk_index,
                },
            )
            for chunk, vec in zip(chunks, vectors)
        ]

        if points:
            self._client.upsert(collection_name=col_name, points=points)

    def _build_bm25(self, chunks: list[Chunk]) -> tuple[BM25Okapi, list[dict]]:
        payloads = [
            {
                "text": chunk.text,
                "source_doc": chunk.source_doc,
                "section_title": chunk.section_title,
                "chunk_index": chunk.chunk_index,
            }
            for chunk in chunks
        ]
        tokenized = [[token.lower() for token in p["text"].split()] for p in payloads]
        bm25 = BM25Okapi(tokenized)
        return bm25, payloads

    def _publish_alias(self, temp_col_name: str, alias_name: str) -> None:
        self._client.update_collection_aliases(
            change_aliases_operations=[
                CreateAliasOperation(
                    create_alias=CreateAlias(
                        collection_name=temp_col_name,
                        alias_name=alias_name,
                    )
                )
            ]
        )

    def _hydrate_bm25_from_qdrant(self, alias_name: str, index_hash: str) -> None:
        all_points = []
        offset = None
        while True:
            points, offset = self._client.scroll(
                collection_name=alias_name,
                limit=256,
                offset=offset,
                with_payload=True,
                with_vectors=False,
            )
            all_points.extend(points)
            if offset is None:
                break

        payloads = [p.payload for p in all_points if p.payload]
        tokenized = [[token.lower() for token in p["text"].split()] for p in payloads]
        bm25 = BM25Okapi(tokenized)
        self._bm25_indices[index_hash] = (bm25, payloads)

    def _delete_collection_if_exists(self, col_name: str) -> None:
        try:
            if self._client.collection_exists(col_name):
                self._client.delete_collection(col_name)
        except Exception:
            pass

