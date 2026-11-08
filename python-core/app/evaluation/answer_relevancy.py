"""
answer_relevancy.py
-------------------
Evaluates semantic answer relevancy against expected ground truth using cosine similarity
of dense embeddings per Plan §2.7.
"""

from __future__ import annotations
import asyncio
from concurrent.futures import ThreadPoolExecutor
import logging
from typing import Callable
import numpy as np

from app.ingestion.embedder import embed_text

logger = logging.getLogger(__name__)

REFUSAL_MESSAGE = "Insufficient local context found to answer safely."


class AnswerRelevancyEvaluator:
    """
    Computes cosine similarity between generated answer and expected ground truth.
    Returns 0.0 for refusal responses and empty answers.
    """

    def __init__(
        self,
        embed_fn: Callable[[str], list[float]] | None = None,
        executor: ThreadPoolExecutor | None = None,
    ) -> None:
        self._embed_fn = embed_fn or embed_text
        self._executor = executor

    def score(self, answer: str, expected_ground_truth: str) -> float:
        """
        Calculates cosine similarity between answer and ground truth embeddings.
        Returns 0.0 for empty strings and fixed refusal messages.
        """
        clean_ans = answer.strip()
        clean_gt = expected_ground_truth.strip()

        if not clean_ans or not clean_gt:
            return 0.0

        if clean_ans == REFUSAL_MESSAGE:
            return 0.0

        vec_ans = np.array(self._embed_fn(clean_ans), dtype=np.float32)
        vec_gt = np.array(self._embed_fn(clean_gt), dtype=np.float32)

        norm_ans = np.linalg.norm(vec_ans)
        norm_gt = np.linalg.norm(vec_gt)

        if norm_ans == 0.0 or norm_gt == 0.0:
            return 0.0

        cosine_sim = float(np.dot(vec_ans, vec_gt) / (norm_ans * norm_gt))
        # Clamp to [0.0, 1.0] for similarity score
        return max(0.0, min(1.0, cosine_sim))

    async def score_async(self, answer: str, expected_ground_truth: str) -> float:
        """Asynchronously evaluates answer relevancy using ThreadPoolExecutor."""
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            self._executor, self.score, answer, expected_ground_truth
        )

