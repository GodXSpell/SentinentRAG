"""
test_faithfulness.py
--------------------
Unit tests for FaithfulnessEvaluator.
"""

from unittest.mock import MagicMock
import numpy as np
import pytest

from app.evaluation.faithfulness import FaithfulnessEvaluator


def test_faithfulness_empty_answer_refusal() -> None:
    evaluator = FaithfulnessEvaluator(model=MagicMock())
    assert evaluator.score("", ["chunk 1"]) == 1.0
    assert evaluator.score("   ", ["chunk 1"]) == 1.0


def test_faithfulness_empty_chunks_non_empty_answer() -> None:
    evaluator = FaithfulnessEvaluator(model=MagicMock())
    assert evaluator.score("Some claim.", []) == 0.0


def test_faithfulness_entailed_and_hallucinated() -> None:
    mock_model = MagicMock()
    # 2 sentences: "First claim." and "Second claim."
    # 1 chunk: "Context chunk."
    # Mock predicts [prob_contradiction, prob_entailment, prob_neutral]
    # Sentence 1: [0.05, 0.90, 0.05] -> entailed (0.90 >= 0.5)
    # Sentence 2: [0.80, 0.10, 0.10] -> hallucination (0.10 < 0.5)
    mock_model.predict.return_value = np.array([
        [0.05, 0.90, 0.05],
        [0.80, 0.10, 0.10],
    ])

    evaluator = FaithfulnessEvaluator(
        model=mock_model,
        entailment_threshold=0.5,
    )

    score = evaluator.score(
        answer="First claim. Second claim.",
        retrieved_chunks=["Context chunk."],
    )

    # 1 out of 2 sentences entailed -> 0.5
    assert score == pytest.approx(0.5)


@pytest.mark.anyio
async def test_faithfulness_async_execution() -> None:
    mock_model = MagicMock()
    mock_model.predict.return_value = np.array([
        [0.01, 0.95, 0.04],
    ])

    evaluator = FaithfulnessEvaluator(
        model=mock_model,
        entailment_threshold=0.5,
    )

    score = await evaluator.score_async(
        answer="Fully supported statement.",
        retrieved_chunks=["Context."],
    )

    assert score == pytest.approx(1.0)

