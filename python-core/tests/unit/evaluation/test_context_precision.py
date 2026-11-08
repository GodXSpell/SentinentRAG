"""
test_context_precision.py
-------------------------
Unit tests for ContextPrecisionEvaluator.
"""

from unittest.mock import MagicMock
import numpy as np
import pytest

from app.evaluation.context_precision import ContextPrecisionEvaluator


def test_context_precision_zero_division_guard() -> None:
    """When sum(relevant) == 0, must return 0.0 without dividing by zero."""
    mock_model = MagicMock()
    # All chunks predict low entailment probability (0.05 < 0.5)
    mock_model.predict.return_value = np.array([
        [0.90, 0.05, 0.05],
        [0.85, 0.05, 0.10],
    ])

    evaluator = ContextPrecisionEvaluator(model=mock_model, entailment_threshold=0.5)
    score = evaluator.score(
        expected_ground_truth="Expected answer.",
        retrieved_chunks=["Chunk 1", "Chunk 2"],
    )

    assert score == 0.0


def test_context_precision_empty_inputs() -> None:
    evaluator = ContextPrecisionEvaluator(model=MagicMock())
    assert evaluator.score("", ["Chunk 1"]) == 0.0
    assert evaluator.score("Expected answer.", []) == 0.0


def test_context_precision_perfect_ranking() -> None:
    mock_model = MagicMock()
    # Both chunks relevant (entailment = 0.9 >= 0.5)
    # rank 1: P@1 = 1/1 = 1.0
    # rank 2: P@2 = 2/2 = 1.0
    # AP = (1.0 + 1.0) / 2 = 1.0
    mock_model.predict.return_value = np.array([
        [0.05, 0.90, 0.05],
        [0.05, 0.90, 0.05],
    ])

    evaluator = ContextPrecisionEvaluator(model=mock_model, entailment_threshold=0.5)
    score = evaluator.score(
        expected_ground_truth="Ground truth.",
        retrieved_chunks=["Chunk 1", "Chunk 2"],
    )

    assert score == pytest.approx(1.0)


def test_context_precision_imperfect_ranking() -> None:
    mock_model = MagicMock()
    # Chunk 1: irrelevant (entailment = 0.1 < 0.5)
    # Chunk 2: relevant (entailment = 0.9 >= 0.5)
    # rank 1: relevant=0
    # rank 2: relevant=1, P@2 = 1/2 = 0.5
    # AP = 0.5 / 1 = 0.5
    mock_model.predict.return_value = np.array([
        [0.80, 0.10, 0.10],
        [0.05, 0.90, 0.05],
    ])

    evaluator = ContextPrecisionEvaluator(model=mock_model, entailment_threshold=0.5)
    score = evaluator.score(
        expected_ground_truth="Ground truth.",
        retrieved_chunks=["Chunk 1", "Chunk 2"],
    )

    assert score == pytest.approx(0.5)


@pytest.mark.anyio
async def test_context_precision_async_execution() -> None:
    mock_model = MagicMock()
    mock_model.predict.return_value = np.array([
        [0.05, 0.85, 0.10],
    ])

    evaluator = ContextPrecisionEvaluator(model=mock_model, entailment_threshold=0.5)
    score = await evaluator.score_async(
        expected_ground_truth="Ground truth.",
        retrieved_chunks=["Chunk 1"],
    )

    assert score == pytest.approx(1.0)

