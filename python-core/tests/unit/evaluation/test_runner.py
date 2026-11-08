"""
test_runner.py
--------------
Unit tests for EvaluationRunner and refusal scoring logic.
"""

from unittest.mock import AsyncMock, MagicMock
import pytest

from app.correction.orchestrator import OrchestratorResult, REFUSAL_MESSAGE
from app.evaluation.pipeline_config import PipelineConfig
from app.evaluation.runner import EvaluationRunner, TestCaseInput, CaseResult


@pytest.fixture
def mock_config() -> PipelineConfig:
    return PipelineConfig(
        prompt_template="Answer: {query}",
        chunk_size_tokens=100,
        chunk_overlap_tokens=20,
        retrieval_top_k=5,
        rerank_top_k=3,
        embedding_model="sentence-transformers/all-MiniLM-L6-v2",
        generator_provider="groq",
        generator_model="llama-3.1-8b-instant",
        corpus_version="v1",
        config_hash="cfg123",
        index_hash="idx123",
    )


@pytest.mark.anyio
async def test_runner_normal_case_success(mock_config: PipelineConfig, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_index_manager = AsyncMock()
    mock_index_manager.get_bm25 = MagicMock(return_value=(MagicMock(), []))

    mock_f_eval = AsyncMock()
    mock_f_eval.score_async.return_value = 0.95
    mock_cp_eval = AsyncMock()
    mock_cp_eval.score_async.return_value = 0.88
    mock_ar_eval = AsyncMock()
    mock_ar_eval.score_async.return_value = 0.91

    # Mock pipeline calls
    fake_chunks = [{"text": "Chunk text", "source_doc": "doc.md", "chunk_index": 0}]
    fake_orch = OrchestratorResult(
        chunks=fake_chunks,
        context_quality_score=0.9,
        self_correction_triggered=False,
        retry_count=0,
        refused=False,
    )
    monkeypatch.setattr("app.evaluation.runner.run_self_correction", lambda *args, **kwargs: fake_orch)
    monkeypatch.setattr("app.evaluation.runner.generate_answer", lambda *args, **kwargs: "Model Answer")
    monkeypatch.setattr("app.evaluation.runner.check_answer", lambda *args, **kwargs: {"clean_answer": "Clean Answer"})

    runner = EvaluationRunner(
        index_manager=mock_index_manager,
        faithfulness_evaluator=mock_f_eval,
        context_precision_evaluator=mock_cp_eval,
        answer_relevancy_evaluator=mock_ar_eval,
    )

    cases = [
        TestCaseInput(
            test_id="case-1",
            query="What is ML?",
            expected_ground_truth="Machine Learning is AI.",
            expects_refusal=False,
        )
    ]

    results = await runner.run_batch(cases, mock_config)

    assert len(results) == 1
    res = results[0]
    assert res.test_id == "case-1"
    assert res.faithfulness_score == 0.95
    assert res.context_precision_score == 0.88
    assert res.answer_relevancy_score == 0.91
    assert res.refused is False
    assert res.refusal_correct is None
    assert res.error == ""


@pytest.mark.anyio
async def test_runner_correct_refusal(mock_config: PipelineConfig, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_index_manager = AsyncMock()
    mock_index_manager.get_bm25 = MagicMock(return_value=None)

    fake_orch = OrchestratorResult(
        chunks=[],
        context_quality_score=0.1,
        self_correction_triggered=True,
        retry_count=1,
        refused=True,
    )
    monkeypatch.setattr("app.evaluation.runner.run_self_correction", lambda *args, **kwargs: fake_orch)

    runner = EvaluationRunner(index_manager=mock_index_manager)

    cases = [
        TestCaseInput(
            test_id="case-refusal",
            query="Adversarial query",
            expected_ground_truth="REFUSAL",
            expects_refusal=True,
        )
    ]

    results = await runner.run_batch(cases, mock_config)
    res = results[0]
    assert res.refused is True
    assert res.refusal_correct is True
    assert res.faithfulness_score == 1.0
    assert res.context_precision_score == 1.0
    assert res.answer_relevancy_score == 1.0
    assert res.generated_answer == REFUSAL_MESSAGE


@pytest.mark.anyio
async def test_runner_unexpected_refusal(mock_config: PipelineConfig, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_index_manager = AsyncMock()
    mock_index_manager.get_bm25 = MagicMock(return_value=None)

    fake_orch = OrchestratorResult(
        chunks=[],
        context_quality_score=0.1,
        self_correction_triggered=False,
        retry_count=0,
        refused=True,
    )
    monkeypatch.setattr("app.evaluation.runner.run_self_correction", lambda *args, **kwargs: fake_orch)

    runner = EvaluationRunner(index_manager=mock_index_manager)

    # expects_refusal = False, but model refused
    cases = [
        TestCaseInput(
            test_id="case-unexpected",
            query="Normal query",
            expected_ground_truth="Expected answer",
            expects_refusal=False,
        )
    ]

    results = await runner.run_batch(cases, mock_config)
    res = results[0]
    assert res.refused is True
    assert res.refusal_correct is False
    assert res.faithfulness_score == 0.0
    assert res.context_precision_score == 0.0
    assert res.answer_relevancy_score == 0.0


@pytest.mark.anyio
async def test_runner_missed_refusal(mock_config: PipelineConfig, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_index_manager = AsyncMock()
    mock_index_manager.get_bm25 = MagicMock(return_value=None)

    fake_orch = OrchestratorResult(
        chunks=[{"text": "Some chunk"}],
        context_quality_score=0.8,
        self_correction_triggered=False,
        retry_count=0,
        refused=False,
    )
    monkeypatch.setattr("app.evaluation.runner.run_self_correction", lambda *args, **kwargs: fake_orch)

    runner = EvaluationRunner(index_manager=mock_index_manager)

    # expects_refusal = True, but model did not refuse
    cases = [
        TestCaseInput(
            test_id="case-missed",
            query="Unsafe query",
            expected_ground_truth="REFUSAL",
            expects_refusal=True,
        )
    ]

    results = await runner.run_batch(cases, mock_config)
    res = results[0]
    assert res.refused is False
    assert res.refusal_correct is False
    assert res.faithfulness_score == 0.0
    assert res.context_precision_score == 0.0
    assert res.answer_relevancy_score == 0.0


@pytest.mark.anyio
async def test_runner_error_isolation(mock_config: PipelineConfig, monkeypatch: pytest.MonkeyPatch) -> None:
    mock_index_manager = AsyncMock()
    mock_index_manager.get_bm25 = MagicMock(return_value=None)

    def broken_self_correction(query, *args, **kwargs):
        if "broken" in query:
            raise RuntimeError("Catastrophic subsystem crash")
        return OrchestratorResult(
            chunks=[{"text": "Good chunk"}],
            context_quality_score=0.9,
            self_correction_triggered=False,
            retry_count=0,
            refused=False,
        )

    monkeypatch.setattr("app.evaluation.runner.run_self_correction", broken_self_correction)
    monkeypatch.setattr("app.evaluation.runner.generate_answer", lambda *args, **kwargs: "Answer")
    monkeypatch.setattr("app.evaluation.runner.check_answer", lambda *args, **kwargs: {"clean_answer": "Clean"})

    mock_f_eval = AsyncMock()
    mock_f_eval.score_async.return_value = 0.9
    mock_cp_eval = AsyncMock()
    mock_cp_eval.score_async.return_value = 0.9
    mock_ar_eval = AsyncMock()
    mock_ar_eval.score_async.return_value = 0.9

    runner = EvaluationRunner(
        index_manager=mock_index_manager,
        faithfulness_evaluator=mock_f_eval,
        context_precision_evaluator=mock_cp_eval,
        answer_relevancy_evaluator=mock_ar_eval,
    )

    cases = [
        TestCaseInput(test_id="good-1", query="normal query 1", expected_ground_truth="gt"),
        TestCaseInput(test_id="bad", query="broken query", expected_ground_truth="gt"),
        TestCaseInput(test_id="good-2", query="normal query 2", expected_ground_truth="gt"),
    ]

    results = await runner.run_batch(cases, mock_config)

    assert len(results) == 3
    assert results[0].test_id == "good-1"
    assert results[0].error == ""
    assert results[0].faithfulness_score == 0.9

    assert results[1].test_id == "bad"
    assert "Catastrophic subsystem crash" in results[1].error
    assert results[1].faithfulness_score == 0.0

    assert results[2].test_id == "good-2"
    assert results[2].error == ""
    assert results[2].faithfulness_score == 0.9
