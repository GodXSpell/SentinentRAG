"""
context_precision.py
--------------------
Evaluates context precision using Average Precision at Rank K (AP@K) per Plan §2.6.
Measures whether relevant chunks (entailing the ground truth) are ranked higher
than irrelevant chunks.
Uses sentence-level hypothesis decomposition and sliding window premise matching.
"""

from __future__ import annotations
import asyncio
from concurrent.futures import ThreadPoolExecutor
import logging
import re
from typing import Any
import numpy as np
from sentence_transformers import CrossEncoder

from app.guard.hallucination_guard import _get_nlp

logger = logging.getLogger(__name__)

DEFAULT_NLI_MODEL = "cross-encoder/nli-deberta-v3-small"
DEFAULT_PRECISION_THRESHOLD = 0.40


class ContextPrecisionEvaluator:
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
        self._nlp = None

    def _get_model(self) -> CrossEncoder:
        if self._model is None:
            self._model = CrossEncoder(self._model_name)
        return self._model

    def _get_nlp(self) -> Any:
        if self._nlp is None:
            self._nlp = _get_nlp()
        return self._nlp

    def _split_sentences(self, text: str) -> list[str]:
        text = text.strip()
        if not text:
            return []
        try:
            nlp = self._get_nlp()
            doc = nlp(text)
            sents = [s.text.strip() for s in doc.sents if len(s.text.strip().split()) >= 3]
            if sents:
                return sents
        except Exception:
            pass
        parts = re.split(r"(?<=[.!?])\s+", text)
        return [p.strip() for p in parts if len(p.strip().split()) >= 3]

    def _build_context_windows(self, chunk: str) -> list[str]:
        raw_sents = self._split_sentences(chunk)
        if not raw_sents:
            return [chunk] if chunk.strip() else []
        windows = [chunk]
        n = len(raw_sents)
        for i in range(n):
            windows.append(raw_sents[i])
            if i >= 1:
                windows.append(f"{raw_sents[i-1]} {raw_sents[i]}")
        return windows

    def is_chunk_relevant(self, chunk: str, gt_sentences: list[str], full_gt: str) -> bool:
        windows = self._build_context_windows(chunk)
        model = self._get_model()

        pairs = []
        # Check full ground truth against full chunk and windows
        pairs.append((chunk, full_gt))
        # Check each ground truth claim against chunk windows
        for s in gt_sentences:
            for w in windows:
                pairs.append((w, s))

        scores = model.predict(pairs, apply_softmax=True)

        for pair_score in scores:
            if isinstance(pair_score, (list, np.ndarray)) and len(pair_score) >= 2:
                prob = float(pair_score[1])
            elif isinstance(pair_score, (float, np.floating)):
                prob = float(pair_score)
            else:
                prob = float(pair_score[0])

            if prob >= self.entailment_threshold:
                return True

        return False

    def score(self, expected_ground_truth: str, retrieved_chunks: list[str]) -> float:
        gt = expected_ground_truth.strip()
        if not gt or not retrieved_chunks:
            return 0.0

        gt_sentences = self._split_sentences(gt)
        if not gt_sentences:
            gt_sentences = [gt]

        relevant: list[int] = []
        for chunk in retrieved_chunks:
            is_rel = 1 if self.is_chunk_relevant(chunk, gt_sentences, gt) else 0
            relevant.append(is_rel)

        total_relevant = sum(relevant)
        if total_relevant == 0:
            return 0.0

        running_relevant = 0
        precision_sum = 0.0

        for rank_1based, is_rel in enumerate(relevant, start=1):
            if is_rel:
                running_relevant += 1
                precision_at_k = running_relevant / rank_1based
                precision_sum += precision_at_k

        return float(precision_sum / total_relevant)

    async def score_async(self, expected_ground_truth: str, retrieved_chunks: list[str]) -> float:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            self._executor, self.score, expected_ground_truth, retrieved_chunks
        )
