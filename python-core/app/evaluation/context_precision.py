"""
context_precision.py
--------------------
Evaluates context precision using Average Precision at Rank K (AP@K) per Plan §2.6.
Measures whether relevant chunks (entailing the ground truth) are ranked higher
than irrelevant chunks.
"""

from __future__ import annotations
import asyncio
from concurrent.futures import ThreadPoolExecutor
import logging
import numpy as np
from sentence_transformers import CrossEncoder

logger = logging.getLogger(__name__)

DEFAULT_NLI_MODEL = "cross-encoder/nli-deberta-v3-small"
DEFAULT_PRECISION_THRESHOLD = 0.5


class ContextPrecisionEvaluator:
    """
    Computes Average Precision at K (AP@K) for retrieved chunks against expected ground truth.
    Uses CrossEncoder NLI to judge chunk relevance (entailment probability >= threshold).
    """

    def __init__(
        self,
        model: CrossEncoder | None = None,
        model_name: str = DEFAULT_NLI_MODEL,
        entailment_threshold: float = DEFAULT_PRECISION_THRESHOLD,
        executor: ThreadPoolExecutor | None = None,
    ) -> None:
        self._model = model
        self._model_name = model_name
        self.entailment_threshold = entailment_threshold
        self._executor = executor

    def _get_model(self) -> CrossEncoder:
        if self._model is None:
            self._model = CrossEncoder(self._model_name)
        return self._model

    def score(self, expected_ground_truth: str, retrieved_chunks: list[str]) -> float:
        """
        Calculates AP@K:
        AP@K = sum(relevant[k] * precision@k) / sum(relevant[k])
        Where relevant[k] = 1 if NLI entailment(chunk, expected_ground_truth) >= threshold, else 0.
        Explicitly returns 0.0 when no chunks are relevant (avoiding division by zero).
        """
        gt = expected_ground_truth.strip()
        if not gt or not retrieved_chunks:
            return 0.0

        model = self._get_model()

        # Build pairs: premise=chunk, hypothesis=ground_truth
        pairs = [(chunk, gt) for chunk in retrieved_chunks]
        scores = model.predict(pairs, apply_softmax=True)

        relevant: list[int] = []
        for pair_score in scores:
            if isinstance(pair_score, (list, np.ndarray)) and len(pair_score) >= 2:
                entail_prob = float(pair_score[1])
            elif isinstance(pair_score, (float, np.floating)):
                entail_prob = float(pair_score)
            else:
                entail_prob = float(pair_score[0])

            relevant.append(1 if entail_prob >= self.entailment_threshold else 0)

        total_relevant = sum(relevant)
        if total_relevant == 0:
            return 0.0  # Explicit 0.0 when no chunks are relevant, avoiding ZeroDivisionError

        running_relevant = 0
        precision_sum = 0.0

        for rank_1based, is_rel in enumerate(relevant, start=1):
            if is_rel:
                running_relevant += 1
                precision_at_k = running_relevant / rank_1based
                precision_sum += precision_at_k

        return float(precision_sum / total_relevant)

    async def score_async(self, expected_ground_truth: str, retrieved_chunks: list[str]) -> float:
        """Asynchronously evaluates context precision using ThreadPoolExecutor."""
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            self._executor, self.score, expected_ground_truth, retrieved_chunks
        )

