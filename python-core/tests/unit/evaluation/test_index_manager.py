"""
test_index_manager.py
---------------------
Unit tests for IndexManager with in-memory QdrantClient.
"""

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock
import pytest
from qdrant_client import QdrantClient

from app.evaluation.index_manager import IndexManager, IndexReadyResult
from app.evaluation.pipeline_config import PipelineConfig, CorpusRef


@pytest.fixture
def sample_config() -> PipelineConfig:
    return PipelineConfig(
        prompt_template="Answer: {query}",
        chunk_size_tokens=100,
        chunk_overlap_tokens=20,
        retrieval_top_k=5,
        rerank_top_k=3,
        embedding_model="sentence-transformers/all-MiniLM-L6-v2",
        generator_provider="groq",
        generator_model="llama-3.1-8b-instant",
        corpus_version="v_test_12345",
        config_hash="c_hash_abcdef",
        index_hash="i_hash_1234567890ab",
        corpus_ref=CorpusRef(
            repo_full_name="GodXSpell/SentinentRAG",
            ref_sha="sha123",
            corpus_path="docs/corpus",
        ),
    )


@pytest.mark.anyio
async def test_ensure_collection_creates_new_index(
    tmp_path: Path, sample_config: PipelineConfig, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Set up in-memory Qdrant client
    qdrant = QdrantClient(":memory:")

    # Mock embed_batch to produce 384-dim dummy embeddings instantly without downloading weights
    monkeypatch.setattr(
        "app.evaluation.index_manager.embed_batch",
        lambda texts: [[0.1] * 384 for _ in texts],
    )

    # Create dummy corpus file
    corpus_dir = tmp_path / sample_config.corpus_version
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.md").write_text("# Section 1\nThis is a sample document for testing.")

    mock_fetcher = AsyncMock()

    manager = IndexManager(
        client=qdrant,
        corpus_fetcher=mock_fetcher,
        corpus_base_dir=tmp_path,
    )

    result = await manager.ensure_collection(sample_config)

    assert isinstance(result, IndexReadyResult)
    assert result.was_created is True
    assert result.collection_name == f"sentinel_{sample_config.index_hash[:12]}"

    # Verify alias exists and points to collection
    aliases = [a.alias_name for a in qdrant.get_aliases().aliases]
    assert result.collection_name in aliases

    # Verify BM25 index is populated
    bm25_entry = manager.get_bm25(sample_config.index_hash)
    assert bm25_entry is not None
    bm25_index, payloads = bm25_entry
    assert len(payloads) >= 1

    # Calling again should return was_created=False immediately
    second_result = await manager.ensure_collection(sample_config)
    assert second_result.was_created is False
    assert second_result.collection_name == result.collection_name


@pytest.mark.anyio
async def test_ensure_collection_cleans_up_temp_on_failure(
    tmp_path: Path, sample_config: PipelineConfig, monkeypatch: pytest.MonkeyPatch
) -> None:
    qdrant = QdrantClient(":memory:")

    corpus_dir = tmp_path / sample_config.corpus_version
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.md").write_text("Valid text")

    manager = IndexManager(
        client=qdrant,
        corpus_base_dir=tmp_path,
    )

    # Force a failure during chunking/embedding
    def failing_chunk(*args, **kwargs):
        raise RuntimeError("Simulated chunking failure")

    monkeypatch.setattr(manager, "_chunk_corpus", failing_chunk)

    with pytest.raises(RuntimeError, match="Simulated chunking failure"):
        await manager.ensure_collection(sample_config)

    # No leftover collections or aliases
    assert len(qdrant.get_collections().collections) == 0
    assert len(qdrant.get_aliases().aliases) == 0
