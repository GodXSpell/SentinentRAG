"""
runner.py
---------
Asynchronous evaluation runner for Sentinel-RAG per Plan §2.8 and §1.3.
Executes test cases concurrently bounded by a Semaphore, accurately measuring latency
after semaphore acquisition and applying the canonical refusal scoring rules.
"""

from __future__ import annotations
import asyncio
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import logging
import time
from typing import Any

from app.config import settings
from app.correction.orchestrator import run_self_correction, OrchestratorResult, REFUSAL_MESSAGE
from app.generation.generate_answer import generate_answer
from app.guard.hallucination_guard import check_answer
from app.evaluation.pipeline_config import PipelineConfig
from app.evaluation.faithfulness import FaithfulnessEvaluator
from app.evaluation.context_precision import ContextPrecisionEvaluator
from app.evaluation.answer_relevancy import AnswerRelevancyEvaluator
from app.evaluation.index_manager import IndexManager

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class TestCaseInput:
    """Input test case specification for evaluation."""
    __test__ = False  # Prevent pytest from attempting to collect as test suite
    test_id: str
    query: str
    expected_ground_truth: str
    expects_refusal: bool = False
    cache_hash: str = ""


@dataclass(frozen=True)
class CaseResult:
    """Result of evaluating a single test case."""
    __test__ = False  # Prevent pytest from attempting to collect as test suite
    test_id: str
    faithfulness_score: float
    context_precision_score: float
    answer_relevancy_score: float
    refusal_correct: bool | None
    execution_time_ms: int
    generated_answer: str
    refused: bool
    retry_count: int
    self_correction_triggered: bool
    error: str = ""


class EvaluationRunner:
    """
    Coordinates evaluation execution across test cases.
    Ensures per-case isolation: an individual case failure never halts the batch.
    """

    def __init__(
        self,
        index_manager: IndexManager,
        faithfulness_evaluator: FaithfulnessEvaluator | None = None,
        context_precision_evaluator: ContextPrecisionEvaluator | None = None,
        answer_relevancy_evaluator: AnswerRelevancyEvaluator | None = None,
        concurrency: int = 5,
        executor: ThreadPoolExecutor | None = None,
    ) -> None:
        self._index_manager = index_manager
        self._faithfulness_evaluator = faithfulness_evaluator or FaithfulnessEvaluator(executor=executor)
        self._context_precision_evaluator = context_precision_evaluator or ContextPrecisionEvaluator(executor=executor)
        self._answer_relevancy_evaluator = answer_relevancy_evaluator or AnswerRelevancyEvaluator(executor=executor)
        self._concurrency = concurrency
        self._semaphore = asyncio.Semaphore(concurrency)
        self._executor = executor

    async def run_batch(
        self,
        cases: list[TestCaseInput],
        config: PipelineConfig,
    ) -> list[CaseResult]:
        """
        Runs evaluation for a batch of test cases under the given PipelineConfig.
        Ensures index is ready before dispatching cases.
        """
        # Ensure index and in-memory BM25 are ready
        await self._index_manager.ensure_collection(config)
        bm25_entry = self._index_manager.get_bm25(config.index_hash)
        bm25_index = bm25_entry[0] if bm25_entry else None
        bm25_chunks = bm25_entry[1] if bm25_entry else None

        tasks = [
            self._evaluate_case(case, config, bm25_index, bm25_chunks)
            for case in cases
        ]
        return await asyncio.gather(*tasks)

    async def _evaluate_case(
        self,
        case: TestCaseInput,
        config: PipelineConfig,
        bm25_index: Any,
        bm25_chunks: list[dict] | None,
    ) -> CaseResult:
        # Acquire semaphore slot BEFORE starting the clock to exclude queue wait time from latency
        async with self._semaphore:
            start_time = time.perf_counter()
            loop = asyncio.get_running_loop()

            try:
                # 1. Self-correction retrieval loop
                orch_result: OrchestratorResult = await loop.run_in_executor(
                    self._executor,
                    run_self_correction,
                    case.query,
                    config,
                    bm25_index,
                    bm25_chunks,
                )

                # 2. Check refusal branch (§1.3)
                if orch_result.refused:
                    execution_time_ms = int((time.perf_counter() - start_time) * 1000)
                    if case.expects_refusal:
                        # Correct refusal: carries 1.0 in records, excluded from score means by Java
                        return CaseResult(
                            test_id=case.test_id,
                            faithfulness_score=1.0,
                            context_precision_score=1.0,
                            answer_relevancy_score=1.0,
                            refusal_correct=True,
                            execution_time_ms=execution_time_ms,
                            generated_answer=REFUSAL_MESSAGE,
                            refused=True,
                            retry_count=orch_result.retry_count,
                            self_correction_triggered=orch_result.self_correction_triggered,
                            error="",
                        )
                    else:
                        # Unexpected refusal: carries 0.0, penalized in score means
                        return CaseResult(
                            test_id=case.test_id,
                            faithfulness_score=0.0,
                            context_precision_score=0.0,
                            answer_relevancy_score=0.0,
                            refusal_correct=False,
                            execution_time_ms=execution_time_ms,
                            generated_answer=REFUSAL_MESSAGE,
                            refused=True,
                            retry_count=orch_result.retry_count,
                            self_correction_triggered=orch_result.self_correction_triggered,
                            error="",
                        )

                # Check if model failed to refuse when refusal was expected
                if case.expects_refusal and not orch_result.refused:
                    # Missed refusal: carries 0.0, included in score means
                    execution_time_ms = int((time.perf_counter() - start_time) * 1000)
                    return CaseResult(
                        test_id=case.test_id,
                        faithfulness_score=0.0,
                        context_precision_score=0.0,
                        answer_relevancy_score=0.0,
                        refusal_correct=False,
                        execution_time_ms=execution_time_ms,
                        generated_answer="[Missed refusal]",
                        refused=False,
                        retry_count=orch_result.retry_count,
                        self_correction_triggered=orch_result.self_correction_triggered,
                        error="",
                    )

                # 3. Normal path: Generation & Guard
                context_text = "\n\n".join(chunk["text"] for chunk in orch_result.chunks)
                chunk_texts = [chunk["text"] for chunk in orch_result.chunks]

                if "{query}" in config.prompt_template:
                    prompt = config.prompt_template.format(query=case.query)
                else:
                    prompt = f"{config.prompt_template}\n\nQuestion: {case.query}"

                raw_answer = await loop.run_in_executor(
                    self._executor,
                    generate_answer,
                    prompt,
                    context_text,
                    config,
                )

                guard_result = await loop.run_in_executor(
                    self._executor,
                    check_answer,
                    raw_answer,
                    context_text,
                )
                clean_answer = guard_result["clean_answer"]

                # 4. Metric Evaluation
                f_score = await self._faithfulness_evaluator.score_async(clean_answer, chunk_texts)
                cp_score = await self._context_precision_evaluator.score_async(case.expected_ground_truth, chunk_texts)
                ar_score = await self._answer_relevancy_evaluator.score_async(clean_answer, case.expected_ground_truth)

                execution_time_ms = int((time.perf_counter() - start_time) * 1000)

                return CaseResult(
                    test_id=case.test_id,
                    faithfulness_score=f_score,
                    context_precision_score=cp_score,
                    answer_relevancy_score=ar_score,
                    refusal_correct=None,  # normal case
                    execution_time_ms=execution_time_ms,
                    generated_answer=clean_answer,
                    refused=False,
                    retry_count=orch_result.retry_count,
                    self_correction_triggered=orch_result.self_correction_triggered,
                    error="",
                )

            except Exception as e:
                logger.error("Error evaluating test_id=%s: %s", case.test_id, e, exc_info=True)
                execution_time_ms = int((time.perf_counter() - start_time) * 1000)
                return CaseResult(
                    test_id=case.test_id,
                    faithfulness_score=0.0,
                    context_precision_score=0.0,
                    answer_relevancy_score=0.0,
                    refusal_correct=None,
                    execution_time_ms=execution_time_ms,
                    generated_answer="",
                    refused=False,
                    retry_count=0,
                    self_correction_triggered=False,
                    error=str(e),
                )
