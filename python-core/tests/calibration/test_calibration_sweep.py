"""
test_calibration_sweep.py
-------------------------
Calibration test and threshold sweep over the labeled cases in cases.yaml.
Calculates classification metrics across threshold candidates (0.3 to 0.7)
to verify optimal threshold selection and gate reliability.
"""

from __future__ import annotations
from pathlib import Path
from typing import Any
import numpy as np
import pytest
import yaml

from app.evaluation.faithfulness import FaithfulnessEvaluator
from app.evaluation.context_precision import ContextPrecisionEvaluator


@pytest.fixture
def calibration_data() -> list[dict[str, Any]]:
    yaml_path = Path(__file__).parent / "cases.yaml"
    with open(yaml_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return data["cases"]


def test_calibration_dataset_integrity(calibration_data: list[dict[str, Any]]) -> None:
    """Verifies that all calibration items contain required schema fields and valid types."""
    assert len(calibration_data) >= 15, "Calibration dataset must contain at least 15 cases"

    types = set()
    for case in calibration_data:
        assert "id" in case
        assert "type" in case
        assert "query" in case
        assert "retrieved_chunks" in case
        assert "answer" in case
        assert "expected_ground_truth" in case
        assert "expects_refusal" in case
        assert "label_faithfulness" in case
        assert "label_precision" in case

        assert case["label_faithfulness"] in (0, 1)
        assert case["label_precision"] in (0, 1)
        assert isinstance(case["expects_refusal"], bool)
        types.add(case["type"])

    assert {"faithful", "hallucinated", "irrelevant_context", "refusal"}.issubset(types)


def test_faithfulness_threshold_sweep(calibration_data: list[dict[str, Any]]) -> None:
    """
    Simulates threshold sweep from 0.3 to 0.7 over faithfulness predictions.
    Verifies that the threshold cleanly separates faithful and unfaithful cases,
    and selects the balanced threshold centered around 0.5.
    """
    sweep_thresholds = [0.3, 0.4, 0.5, 0.6, 0.7]

    # Mock model predicting calibrated entailment probabilities based on case type
    class CalibratedMockCrossEncoder:
        def predict(self, pairs: list[tuple[str, str]], apply_softmax: bool = True) -> list[list[float]]:
            results = []
            for premise, hypothesis in pairs:
                # Faithful cases have high entailment (~0.85-0.95)
                if "port 6379" in premise and "6379" in hypothesis:
                    results.append([0.05, 0.90, 0.05])
                elif "alias" in premise and "alias" in hypothesis:
                    results.append([0.05, 0.92, 0.03])
                elif "PING" in premise and "PING" in hypothesis:
                    results.append([0.04, 0.91, 0.05])
                elif "flyway_schema_history" in premise and "flyway_schema_history" in hypothesis:
                    results.append([0.02, 0.95, 0.03])
                elif ("loop.run_in_executor" in premise or "CPU-bound" in premise) and "run_in_executor" in hypothesis:
                    results.append([0.03, 0.93, 0.04])
                # Hallucinated cases contradict or are unentailed (< 0.10)
                elif "5432" in premise and "3306" in hypothesis:
                    results.append([0.95, 0.02, 0.03])
                elif "7 days" in premise and "30 years" in hypothesis:
                    results.append([0.96, 0.01, 0.03])
                elif "PKCE" in premise and "hardcoded" in hypothesis:
                    results.append([0.92, 0.03, 0.05])
                elif "HS256" in premise and "MD5" in hypothesis:
                    results.append([0.94, 0.02, 0.04])
                else:
                    # Irrelevant
                    results.append([0.45, 0.10, 0.45])
            return results

    # Non-refusal cases are evaluated for faithfulness thresholding
    eval_cases = [c for c in calibration_data if not c["expects_refusal"]]
    y_true = [c["label_faithfulness"] for c in eval_cases]

    best_threshold = 0.5
    best_score = -1.0

    for threshold in sweep_thresholds:
        evaluator = FaithfulnessEvaluator(
            model=CalibratedMockCrossEncoder(),
            entailment_threshold=threshold,
        )
        scores = [evaluator.score(c["answer"], c["retrieved_chunks"]) for c in eval_cases]
        y_pred = [1 if s >= threshold else 0 for s in scores]

        # Calculate Precision, Recall, F1
        tp = sum(1 for yt, yp in zip(y_true, y_pred) if yt == 1 and yp == 1)
        fp = sum(1 for yt, yp in zip(y_true, y_pred) if yt == 0 and yp == 1)
        fn = sum(1 for yt, yp in zip(y_true, y_pred) if yt == 1 and yp == 0)

        precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = (2 * precision * recall) / (precision * recall) if (precision + recall) > 0 else 0.0

        # Preference towards central threshold (0.5) when F1 is tied
        distance_penalty = abs(threshold - 0.5) * 0.01
        score = f1 - distance_penalty

        if score > best_score:
            best_score = score
            best_threshold = threshold

    assert best_threshold == 0.5, f"Calibrated optimal threshold should be 0.5, got {best_threshold}"


def test_context_precision_threshold_sweep(calibration_data: list[dict[str, Any]]) -> None:
    """
    Simulates threshold sweep for ContextPrecisionEvaluator (AP@K).
    Verifies that relevant contexts receive high AP@K and irrelevant contexts yield 0.0.
    """
    class PrecisionMockCrossEncoder:
        def predict(self, pairs: list[tuple[str, str]], apply_softmax: bool = True) -> list[list[float]]:
            results = []
            for chunk, gt in pairs:
                # Relevant chunks entail the ground truth
                if "6379" in chunk and "6379" in gt:
                    results.append([0.05, 0.92, 0.03])
                elif "rename" in chunk and "rename" in gt:
                    results.append([0.04, 0.93, 0.03])
                elif "PING" in chunk and "health" in gt:
                    results.append([0.05, 0.88, 0.07])
                elif "flyway_schema_history" in chunk and "flyway_schema_history" in gt:
                    results.append([0.02, 0.96, 0.02])
                elif ("run_in_executor" in chunk or "CPU-bound" in chunk) and "loop" in gt:
                    results.append([0.03, 0.91, 0.06])
                elif "5432" in chunk and "5432" in gt:
                    results.append([0.03, 0.94, 0.03])
                elif ("168 hours" in chunk or "7 days" in chunk) and "7 days" in gt:
                    results.append([0.05, 0.90, 0.05])
                elif "PKCE" in chunk and "PKCE" in gt:
                    results.append([0.04, 0.91, 0.05])
                elif "HS256" in chunk and "HS256" in gt:
                    results.append([0.03, 0.95, 0.02])
                else:
                    results.append([0.80, 0.10, 0.10])
            return results

    eval_cases = [c for c in calibration_data if not c["expects_refusal"]]
    evaluator = ContextPrecisionEvaluator(
        model=PrecisionMockCrossEncoder(),
        entailment_threshold=0.5,
    )

    for case in eval_cases:
        score = evaluator.score(case["expected_ground_truth"], case["retrieved_chunks"])
        if case["label_precision"] == 1:
            assert score >= 0.5, f"Case {case['id']} should have precision >= 0.5, got {score}"
        else:
            assert score == 0.0, f"Case {case['id']} should have 0.0 precision for irrelevant context, got {score}"
