"""
service.py
----------
Async gRPC Servicer implementing the RagEngine service:
- GetEngineInfo: returns engine commit version and model IDs
- QueryRAG: real-time inference with self-correction
- PrepareIndex: ensures Qdrant collection and BM25 index are ready under a long deadline
- RunEvaluation: executes evaluation batch on cache misses
"""

from __future__ import annotations
import asyncio
from concurrent.futures import ThreadPoolExecutor
import logging
import os
import time
import grpc

from app.grpc.generated import rag_service_pb2, rag_service_pb2_grpc
from app.evaluation.pipeline_config import PipelineConfig
from app.evaluation.corpus_fetcher import CorpusFetchError
from app.evaluation.index_manager import IndexManager
from app.evaluation.runner import EvaluationRunner, TestCaseInput
from app.correction.orchestrator import run_self_correction
from app.generation.generate_answer import generate_answer
from app.guard.hallucination_guard import check_answer
from app.retrieval.reranker import RERANKER_MODEL_NAME
from app.evaluation.faithfulness import DEFAULT_NLI_MODEL
from app.config import settings

logger = logging.getLogger(__name__)

DEFAULT_ENGINE_VERSION = os.getenv("SENTINEL_ENGINE_VERSION", "5ae17ff")


class RagEngineServicer(rag_service_pb2_grpc.RagEngineServicer):
    """
    gRPC Servicer using grpc.aio and async evaluation runner.
    """

    def __init__(
        self,
        index_manager: IndexManager,
        runner: EvaluationRunner,
        engine_version: str = DEFAULT_ENGINE_VERSION,
        evaluator_model: str = DEFAULT_NLI_MODEL,
        reranker_model: str = RERANKER_MODEL_NAME,
        embedding_model: str = settings.embedding_model_name,
        executor: ThreadPoolExecutor | None = None,
    ) -> None:
        self._index_manager = index_manager
        self._runner = runner
        self._engine_version = engine_version
        self._evaluator_model = evaluator_model
        self._reranker_model = reranker_model
        self._embedding_model = embedding_model
        self._executor = executor

    async def GetEngineInfo(
        self,
        request: rag_service_pb2.Empty,
        context: grpc.aio.ServicerContext,
    ) -> rag_service_pb2.EngineInfoResponse:
        """
        Returns metadata about the deployed engine (commit version and active model identifiers).
        Used by the Java plane to construct config_hash and ensure automatic cache invalidation
        across code redeployments or model updates.
        """
        return rag_service_pb2.EngineInfoResponse(
            engine_version=self._engine_version,
            evaluator_model=self._evaluator_model,
            reranker_model=self._reranker_model,
            embedding_model=self._embedding_model,
        )

    async def QueryRAG(
        self,
        request: rag_service_pb2.RAGQueryRequest,
        context: grpc.aio.ServicerContext,
    ) -> rag_service_pb2.RAGQueryResponse:
        """
        Synchronous-style RAG inference query using the self-correction orchestrator.
        """
        start_time = time.perf_counter()
        loop = asyncio.get_running_loop()

        try:
            orch_result = await loop.run_in_executor(
                self._executor,
                run_self_correction,
                request.query,
            )

            if orch_result.refused:
                latency_ms = int((time.perf_counter() - start_time) * 1000)
                return rag_service_pb2.RAGQueryResponse(
                    answer="Insufficient local context found to answer safely.",
                    retrieved_chunks=[],
                    context_quality_score=orch_result.context_quality_score,
                    self_correction_triggered=orch_result.self_correction_triggered,
                    retry_count=orch_result.retry_count,
                    latency_ms=latency_ms,
                )

            context_text = "\n\n".join(chunk["text"] for chunk in orch_result.chunks)
            raw_answer = await loop.run_in_executor(
                self._executor,
                generate_answer,
                request.query,
                context_text,
            )

            guard_result = await loop.run_in_executor(
                self._executor,
                check_answer,
                raw_answer,
                context_text,
            )

            latency_ms = int((time.perf_counter() - start_time) * 1000)
            return rag_service_pb2.RAGQueryResponse(
                answer=guard_result["clean_answer"],
                retrieved_chunks=[chunk["text"] for chunk in orch_result.chunks],
                context_quality_score=orch_result.context_quality_score,
                self_correction_triggered=orch_result.self_correction_triggered,
                retry_count=orch_result.retry_count,
                latency_ms=latency_ms,
            )

        except Exception as e:
            logger.error("Error handling QueryRAG: %s", e, exc_info=True)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))

    async def PrepareIndex(
        self,
        request: rag_service_pb2.PrepareIndexRequest,
        context: grpc.aio.ServicerContext,
    ) -> rag_service_pb2.PrepareIndexResponse:
        """
        Prepares and verifies the Qdrant and BM25 index for the supplied PipelineConfig.
        Runs under a longer deadline (e.g. 600 s) so indexing never races against evaluation.
        """
        try:
            config = PipelineConfig.from_proto(request.config)
            result = await self._index_manager.ensure_collection(config)
            return rag_service_pb2.PrepareIndexResponse(
                collection_name=result.collection_name,
                was_created=result.was_created,
                duration_ms=result.duration_ms,
            )
        except ValueError as e:
            logger.warning("Invalid configuration for PrepareIndex: %s", e)
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
        except CorpusFetchError as e:
            logger.error("Corpus fetch error in PrepareIndex: %s", e)
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(e))
        except Exception as e:
            logger.error("Unexpected failure in PrepareIndex: %s", e, exc_info=True)
            await context.abort(grpc.StatusCode.INTERNAL, "Internal index preparation failure")

    async def RunEvaluation(
        self,
        request: rag_service_pb2.EvalBatchRequest,
        context: grpc.aio.ServicerContext,
    ) -> rag_service_pb2.EvalBatchResponse:
        """
        Evaluates a batch of test cases (only cache misses) under the supplied PipelineConfig.
        """
        start_time = time.perf_counter()
        try:
            config = PipelineConfig.from_proto(request.config)
            cases = [
                TestCaseInput(
                    test_id=tc.test_id,
                    query=tc.query,
                    expected_ground_truth=tc.expected_ground_truth,
                    expects_refusal=getattr(tc, "expects_refusal", False),
                    cache_hash=tc.cache_hash,
                )
                for tc in request.test_cases
            ]

            results = await self._runner.run_batch(cases, config)

            proto_results = [
                rag_service_pb2.TestResult(
                    test_id=r.test_id,
                    faithfulness_score=r.faithfulness_score,
                    context_precision_score=r.context_precision_score,
                    answer_relevancy_score=r.answer_relevancy_score,
                    execution_time_ms=r.execution_time_ms,
                    was_cached=False,
                    generated_answer=r.generated_answer,
                    error=r.error,
                    refused=r.refused,
                    retry_count=r.retry_count,
                    self_correction_triggered=r.self_correction_triggered,
                )
                for r in results
            ]

            mean_faithfulness = (
                sum(r.faithfulness_score for r in results) / len(results)
                if results else 0.0
            )
            mean_context_precision = (
                sum(r.context_precision_score for r in results) / len(results)
                if results else 0.0
            )
            total_duration_ms = int((time.perf_counter() - start_time) * 1000)

            return rag_service_pb2.EvalBatchResponse(
                suite_id=request.suite_id,
                results=proto_results,
                mean_faithfulness=mean_faithfulness,
                mean_context_precision=mean_context_precision,
                total_duration_ms=total_duration_ms,
            )

        except ValueError as e:
            logger.warning("Invalid argument in RunEvaluation: %s", e)
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(e))
        except CorpusFetchError as e:
            logger.error("Failed precondition in RunEvaluation: %s", e)
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(e))
        except Exception as e:
            logger.error("Internal error in RunEvaluation: %s", e, exc_info=True)
            await context.abort(grpc.StatusCode.INTERNAL, "Internal evaluation runner failure")

