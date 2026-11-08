"""
pipeline_config.py
------------------
Data models for evaluation pipeline configuration.
Passed explicitly through retrieval, reranking, generation, and evaluation calls.
"""

from __future__ import annotations
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class CorpusRef:
    """References the corpus location in GitHub to fetch at a specific commit SHA."""
    repo_full_name: str
    ref_sha: str
    corpus_path: str = "docs/corpus"

    @classmethod
    def from_proto(cls, proto_obj: Any) -> CorpusRef:
        return cls(
            repo_full_name=proto_obj.repo_full_name,
            ref_sha=proto_obj.ref_sha,
            corpus_path=proto_obj.corpus_path or "docs/corpus",
        )


@dataclass(frozen=True)
class PipelineConfig:
    """
    Immutable evaluation configuration passed through retrieval and generation pipelines.
    Eliminates reliance on global module-level settings during evaluation runs.
    """
    prompt_template: str
    chunk_size_tokens: int
    chunk_overlap_tokens: int
    retrieval_top_k: int
    rerank_top_k: int
    embedding_model: str
    generator_provider: str
    generator_model: str
    corpus_version: str
    config_hash: str
    index_hash: str
    corpus_ref: CorpusRef | None = None

    @property
    def collection_name(self) -> str:
        """Name of the ready Qdrant alias/collection for this index_hash."""
        return f"sentinel_{self.index_hash[:12]}"

    @classmethod
    def from_proto(cls, proto_obj: Any) -> PipelineConfig:
        corpus_ref = None
        if proto_obj.HasField("corpus_ref"):
            corpus_ref = CorpusRef.from_proto(proto_obj.corpus_ref)

        return cls(
            prompt_template=proto_obj.prompt_template,
            chunk_size_tokens=proto_obj.chunk_size_tokens,
            chunk_overlap_tokens=proto_obj.chunk_overlap_tokens,
            retrieval_top_k=proto_obj.retrieval_top_k,
            rerank_top_k=proto_obj.rerank_top_k,
            embedding_model=proto_obj.embedding_model,
            generator_provider=proto_obj.generator_provider,
            generator_model=proto_obj.generator_model,
            corpus_version=proto_obj.corpus_version,
            config_hash=proto_obj.config_hash,
            index_hash=proto_obj.index_hash,
            corpus_ref=corpus_ref,
        )

