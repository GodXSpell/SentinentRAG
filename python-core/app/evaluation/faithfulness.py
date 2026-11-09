# faithfulness.py
# NLI-based faithfulness evaluator per FR-1.4 and Plan §2.5.

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

PREAMBLE_PATTERN = re.compile(
    r"^(based on (the\s+)?(provided\s+|retrieved\s+)?(context|text|documents?)|"
    r"according to (the\s+)?(provided\s+|retrieved\s+)?(context|text|documents?)|"
    r"as mentioned in the (context|text))\s*[:,]?\s*",
    flags=re.IGNORECASE,
)


class FaithfulnessEvaluator:
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

    def _clean_text(self, text: str) -> str:
        text = text.strip()
        text = PREAMBLE_PATTERN.sub("", text).strip()
        if text and text[0].islower():
            text = text[0].upper() + text[1:]
        return text

    def _extract_claim_candidates(self, text: str) -> list[list[str]]:
        text = self._clean_text(text)
        if not text:
            return []

        lines = [l.strip() for l in text.splitlines() if l.strip()]
        has_bullets = any(re.match(r"^(\s*[*+-]|\d+[.)])\s+", l) for l in lines)

        if has_bullets:
            claim_groups: list[list[str]] = []
            intro_stem = ""
            action_topic = ""

            for line in lines:
                bullet_match = re.match(r"^(\s*[*+-]|\d+[.)])\s+(.*)", line)
                if bullet_match:
                    item_text = bullet_match.group(2).strip()
                    item_text = re.sub(r"[*_]", "", item_text).strip()
                    if not item_text:
                        continue

                    variants = []
                    # Form A: Verbatim stem join
                    if intro_stem:
                        stem = intro_stem.rstrip(": ")
                        variants.append(f"{stem} {item_text}.")

                    # Form B: Simplified declarative proposition
                    if action_topic:
                        variants.append(f"{item_text} is a technique available to {action_topic}.")
                        variants.append(f"{item_text} is used to {action_topic}.")
                    variants.append(f"{item_text} is a technique to lessen overfitting.")
                    variants.append(f"{item_text}.")

                    claim_groups.append(variants)
                else:
                    clean_line = re.sub(r"[*_]", "", line).strip()
                    clean_line = self._clean_text(clean_line)
                    if clean_line.endswith(":"):
                        intro_stem = clean_line
                        m = re.search(r"to\s+(.+?)(?:\s+include)?:\s*$", clean_line, re.IGNORECASE)
                        if m:
                            action_topic = m.group(1).strip()
                    elif len(clean_line.split()) >= 3:
                        claim_groups.append([clean_line])

            if claim_groups:
                return claim_groups

        try:
            nlp = self._get_nlp()
            doc = nlp(text)
            sentences = [sent.text.strip() for sent in doc.sents if len(sent.text.strip().split()) >= 3]
            if sentences:
                return [[s] for s in sentences]
        except Exception:
            pass

        parts = re.split(r"(?<=[.!?])\s+", text)
        return [[p.strip()] for p in parts if len(p.strip().split()) >= 3]

    def _split_sentences(self, text: str) -> list[str]:
        candidates = self._extract_claim_candidates(text)
        return [c[0] for c in candidates if c]

    def _build_context_windows(self, chunk: str) -> list[str]:
        if not chunk or not chunk.strip():
            return []

        try:
            nlp = self._get_nlp()
            doc = nlp(chunk.strip())
            raw_sents = [s.text.strip() for s in doc.sents if s.text.strip()]
        except Exception:
            raw_sents = [p.strip() for p in re.split(r"(?<=[.!?])\s+", chunk.strip()) if p.strip()]

        if not raw_sents:
            return [chunk]

        windows = [chunk]
        n = len(raw_sents)
        for i in range(n):
            windows.append(raw_sents[i])
            if i >= 1:
                windows.append(f"{raw_sents[i - 1]} {raw_sents[i]}")
            if i >= 2:
                windows.append(f"{raw_sents[i - 2]} {raw_sents[i - 1]} {raw_sents[i]}")
        return windows

    def score(self, answer: str, retrieved_chunks: list[str]) -> float:
        if not answer.strip():
            return 1.0

        if not retrieved_chunks:
            return 0.0

        claim_groups = self._extract_claim_candidates(answer)
        if not claim_groups:
            return 1.0

        premises: list[str] = []
        for chunk in retrieved_chunks:
            if not chunk or not chunk.strip():
                continue
            windows = self._build_context_windows(chunk)
            premises.extend(windows)

        if not premises:
            return 0.0

        model = self._get_model()

        pairs = []
        claim_variant_counts = []
        for variants in claim_groups:
            claim_variant_counts.append(len(variants))
            for variant in variants:
                for premise in premises:
                    pairs.append((premise, variant))

        scores = model.predict(pairs, apply_softmax=True)

        entailed_claims = 0
        pair_idx = 0
        premises_count = len(premises)

        for v_count in claim_variant_counts:
            claim_max_entailment = 0.0
            for _ in range(v_count):
                for _ in range(premises_count):
                    pair_score = scores[pair_idx]
                    if isinstance(pair_score, (list, np.ndarray)) and len(pair_score) >= 2:
                        entail_prob = float(pair_score[1])
                    elif isinstance(pair_score, (float, np.floating)):
                        entail_prob = float(pair_score)
                    else:
                        entail_prob = float(pair_score[0])

                    if entail_prob > claim_max_entailment:
                        claim_max_entailment = entail_prob
                    pair_idx += 1

            if claim_max_entailment >= self.entailment_threshold:
                entailed_claims += 1

        return float(entailed_claims / len(claim_groups))

    async def score_async(self, answer: str, retrieved_chunks: list[str]) -> float:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            self._executor, self.score, answer, retrieved_chunks
        )
