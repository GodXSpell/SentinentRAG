"""
test_service.py
---------------
Unit tests for RagEngineServicer.
"""

from unittest.mock import AsyncMock, MagicMock
import pytest

from app.grpc.generated import rag_service_pb2
from app.evaluation.index_manager import IndexReadyResult
from app.evaluation.runner import CaseResult
from grpc_server.service import RagEngineServicer


@pytest.fixture
def servicer_bundle() -> tuple[RagEngineServicer, AsyncMock, AsyncMock]:
    mock_index_mgr = AsyncMock()
    mock_runner = AsyncMock()
    servicer = RagEngineServicer(
        index_manager=mock_index_mgr,
        runner=mock_runner,
        engine_version="test-commit-sha",
        evaluator_model="test-nli-model",
        reranker_model="test-reranker-model",
        embedding_model="test-embedding-model",
    )
    return servicer, mock_index_mgr, mock_runner


@pytest.mark.anyio
async def test_get_engine_info(servicer_bundle: tuple) -> None:
    servicer, _, _ = servicer_bundle
    mock_context = AsyncMock()

    response = await servicer.GetEngineInfo(rag_service_pb2.Empty(), mock_context)

    assert response.engine_version == "test-commit-sha"
    assert response.evaluator_model == "test-nli-model"
    assert response.reranker_model == "test-reranker-model"
    assert response.embedding_model == "test-embedding-model"


@pytest.mark.anyio
async def test_prepare_index(servicer_bundle: tuple) -> None:
    servicer, mock_index_mgr, _ = servicer_bundle
    mock_index_mgr.ensure_collection.return_value = IndexReadyResult(
        collection_name="sentinel_abcdef123456",
        was_created=True,
        duration_ms=450,
    )
    mock_context = AsyncMock()

    proto_config = rag_service_pb2.PipelineConfig(
        prompt_template="Answer: {query}",
        chunk_size_tokens=100,
        chunk_overlap_tokens=20,
        retrieval_top_k=5,
        rerank_top_k=3,
        embedding_model="model",
        generator_provider="groq",
        generator_model="llama",
        corpus_version="cv",
        config_hash="ch",
        index_hash="abcdef123456",
    )
    request = rag_service_pb2.PrepareIndexRequest(config=proto_config)

    response = await servicer.PrepareIndex(request, mock_context)

    assert response.collection_name == "sentinel_abcdef123456"
    assert response.was_created is True
    assert response.duration_ms == 450


@pytest.mark.anyio
async def test_run_evaluation(servicer_bundle: tuple) -> None:
    servicer, _, mock_runner = servicer_bundle
    mock_context = AsyncMock()

    mock_runner.run_batch.return_value = [
        CaseResult(
            test_id="tc-1",
            faithfulness_score=0.92,
            context_precision_score=0.85,
            answer_relevancy_score=0.89,
            refusal_correct=None,
            execution_time_ms=120,
            generated_answer="Answer 1",
            refused=False,
            retry_count=0,
            self_correction_triggered=False,
        ),
        CaseResult(
            test_id="tc-refused",
            faithfulness_score=1.0,
            context_precision_score=1.0,
            answer_relevancy_score=1.0,
            refusal_correct=True,
            execution_time_ms=50,
            generated_answer="Insufficient local context found to answer safely.",
            refused=True,
            retry_count=1,
            self_correction_triggered=True,
        ),
    ]

    proto_config = rag_service_pb2.PipelineConfig(
        prompt_template="Answer: {query}",
        chunk_size_tokens=100,
        chunk_overlap_tokens=20,
        retrieval_top_k=5,
        rerank_top_k=3,
        embedding_model="model",
        generator_provider="groq",
        generator_model="llama",
        corpus_version="cv",
        config_hash="ch",
        index_hash="abcdef123456",
    )

    request = rag_service_pb2.EvalBatchRequest(
        suite_id="suite-1",
        test_cases=[
            rag_service_pb2.TestCase(test_id="tc-1", query="Query 1", expected_ground_truth="GT 1"),
            rag_service_pb2.TestCase(test_id="tc-refused", query="Query 2", expected_ground_truth="REFUSAL"),
        ],
        config=proto_config,
    )

    response = await servicer.RunEvaluation(request, mock_context)

    assert response.suite_id == "suite-1"
    assert len(response.results) == 2

    r0 = response.results[0]
    assert r0.test_id == "tc-1"
    assert r0.faithfulness_score == pytest.approx(0.92)
    assert r0.refused is False

    r1 = response.results[1]
    assert r1.test_id == "tc-refused"
    assert r1.refused is True
    assert r1.retry_count == 1
    assert r1.self_correction_triggered is True

