"""
faithfulness.py
---------------
NLI-based faithfulness evaluator per FR-1.4 and Plan §2.5.
Measures the fraction of sentences in the generated answer that are
entailed by the retrieved context chunks.
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
DEFAULT_ENTAILMENT_THRESHOLD = 0.5


class FaithfulnessEvaluator:
    """
    Evaluates answer faithfulness against retrieved chunks.
    Uses spaCy for sentence segmentation and a CrossEncoder NLI model.
    Inference is executed in a ThreadPoolExecutor to prevent event loop blocking.
    """

    def __init__(
        self,
        model: CrossEncoder | None = None,
        model_name: str = DEFAULT_NLI_MODEL,
        entailment_threshold: float = DEFAULT_ENTAILMENT_THRESHOLD,
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
            sentences = [sent.text.strip() for sent in doc.sents if sent.text.strip()]
            if sentences:
                return sentences
        except Exception:
            pass
        # Fallback to regex sentence split if needed
        parts = re.split(r"(?<=[.!?])\s+", text)
        return [p.strip() for p in parts if p.strip()]

    def score(self, answer: str, retrieved_chunks: list[str]) -> float:
        """
        Synchronous scoring function:
        1. An empty answer returns 1.0 (refusal is not a hallucination; refusal scoring is handled separately).
        2. If no chunks exist and answer is non-empty, returns 0.0.
        3. For each sentence, computes max entailment across retrieved chunks.
        4. Score is fraction of sentences exceeding entailment_threshold.
        """
        if not answer.strip():
            return 1.0

        if not retrieved_chunks:
            return 0.0

        sentences = self._split_sentences(answer)
        if not sentences:
            return 1.0

        model = self._get_model()

        # Build all (chunk, sentence) pairs: premise=chunk, hypothesis=sentence
        pairs = []
        for sent in sentences:
            for chunk in retrieved_chunks:
                pairs.append((chunk, sent))

        # Predict with softmax over [contradiction, entailment, neutral]
        scores = model.predict(pairs, apply_softmax=True)

        # Parse entailment score (index 1)
        entailed_sentences = 0
        pair_idx = 0
        chunks_count = len(retrieved_chunks)

        for _ in sentences:
            sentence_max_entailment = 0.0
            for _ in range(chunks_count):
                pair_score = scores[pair_idx]
                if isinstance(pair_score, (list, np.ndarray)) and len(pair_score) >= 2:
                    entail_prob = float(pair_score[1])
                elif isinstance(pair_score, (float, np.floating)):
                    entail_prob = float(pair_score)
                else:
                    entail_prob = float(pair_score[0])

                if entail_prob > sentence_max_entailment:
                    sentence_max_entailment = entail_prob
                pair_idx += 1

            if sentence_max_entailment >= self.entailment_threshold:
                entailed_sentences += 1

        return float(entailed_sentences / len(sentences))

    async def score_async(self, answer: str, retrieved_chunks: list[str]) -> float:
        """Asynchronously evaluates faithfulness using ThreadPoolExecutor."""
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            self._executor, self.score, answer, retrieved_chunks
        )

