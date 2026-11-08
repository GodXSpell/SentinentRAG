"""
test_answer_relevancy.py
------------------------
Unit tests for AnswerRelevancyEvaluator.
"""

from unittest.mock import MagicMock
import pytest

from app.evaluation.answer_relevancy import AnswerRelevancyEvaluator, REFUSAL_MESSAGE


def test_answer_relevancy_refusal_returns_zero() -> None:
    evaluator = AnswerRelevancyEvaluator(embed_fn=MagicMock())
    assert evaluator.score(REFUSAL_MESSAGE, "Some ground truth.") == 0.0


def test_answer_relevancy_empty_inputs_return_zero() -> None:
    evaluator = AnswerRelevancyEvaluator(embed_fn=MagicMock())
    assert evaluator.score("", "Ground truth.") == 0.0
    assert evaluator.score("Answer.", "") == 0.0
    assert evaluator.score("   ", "   ") == 0.0


def test_answer_relevancy_identical_vectors_return_one() -> None:
    mock_embed = lambda text: [1.0, 0.0, 0.0]
    evaluator = AnswerRelevancyEvaluator(embed_fn=mock_embed)

    score = evaluator.score("Identical text", "Identical text")
    assert score == pytest.approx(1.0)


def test_answer_relevancy_orthogonal_vectors_return_zero() -> None:
    mock_embed = lambda text: [1.0, 0.0] if "A" in text else [0.0, 1.0]
    evaluator = AnswerRelevancyEvaluator(embed_fn=mock_embed)

    score = evaluator.score("A", "B")
    assert score == pytest.approx(0.0)


@pytest.mark.anyio
async def test_answer_relevancy_async_execution() -> None:
    mock_embed = lambda text: [0.6, 0.8]
    evaluator = AnswerRelevancyEvaluator(embed_fn=mock_embed)

    score = await evaluator.score_async("Answer", "Ground truth")
    assert score == pytest.approx(1.0)

