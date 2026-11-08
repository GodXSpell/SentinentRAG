"""
server.py
---------
Entrypoint for the Sentinel-RAG async gRPC server.
Preloads models once at startup, wires dependency injection, and binds grpc.aio.server.
"""

from __future__ import annotations
import asyncio
from concurrent.futures import ThreadPoolExecutor
import logging
import os
import sys

import grpc
from sentence_transformers import SentenceTransformer, CrossEncoder

from app.config import settings
from app.grpc.generated import rag_service_pb2_grpc
from app.guard.hallucination_guard import _get_nlp
from app.evaluation.corpus_fetcher import CorpusFetcher
from app.evaluation.index_manager import IndexManager
from app.evaluation.faithfulness import FaithfulnessEvaluator, DEFAULT_NLI_MODEL
from app.evaluation.context_precision import ContextPrecisionEvaluator
from app.evaluation.answer_relevancy import AnswerRelevancyEvaluator
from app.evaluation.runner import EvaluationRunner
from app.retrieval.reranker import RERANKER_MODEL_NAME
from grpc_server.service import RagEngineServicer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("grpc_server")


async def create_server(host: str = "0.0.0.0", port: int = 9090) -> grpc.aio.Server:
    """
    Creates and initializes the async gRPC server with preloaded models and thread pool.
    """
    logger.info("Initializing Sentinel-RAG Evaluation & Inference gRPC Server...")

    executor = ThreadPoolExecutor(max_workers=os.cpu_count() or 4)

    # 1. Preload models into memory
    logger.info("Preloading embedding model: %s", settings.embedding_model_name)
    try:
        embedding_model = SentenceTransformer(settings.embedding_model_name)
    except Exception as e:
        logger.warning("Could not preload embedding model: %s", e)
        embedding_model = None

    logger.info("Preloading reranker model: %s", RERANKER_MODEL_NAME)
    try:
        reranker_model = CrossEncoder(RERANKER_MODEL_NAME)
    except Exception as e:
        logger.warning("Could not preload reranker model: %s", e)
        reranker_model = None

    logger.info("Preloading NLI evaluator model: %s", DEFAULT_NLI_MODEL)
    try:
        nli_model = CrossEncoder(DEFAULT_NLI_MODEL)
    except Exception as e:
        logger.warning("Could not preload NLI model: %s", e)
        nli_model = None

    logger.info("Preloading spaCy NER model...")
    try:
        _get_nlp()
    except Exception as e:
        logger.warning("Could not preload spaCy model: %s", e)

    # 2. Wire dependency graph
    corpus_fetcher = CorpusFetcher()
    index_manager = IndexManager(corpus_fetcher=corpus_fetcher)

    faithfulness_evaluator = FaithfulnessEvaluator(
        model=nli_model,
        executor=executor,
    )
    context_precision_evaluator = ContextPrecisionEvaluator(
        model=nli_model,
        executor=executor,
    )
    answer_relevancy_evaluator = AnswerRelevancyEvaluator(
        embed_fn=embedding_model.encode if embedding_model else None,
        executor=executor,
    )

    runner = EvaluationRunner(
        index_manager=index_manager,
        faithfulness_evaluator=faithfulness_evaluator,
        context_precision_evaluator=context_precision_evaluator,
        answer_relevancy_evaluator=answer_relevancy_evaluator,
        concurrency=settings.eval_concurrency,
        executor=executor,
    )

    servicer = RagEngineServicer(
        index_manager=index_manager,
        runner=runner,
        executor=executor,
    )

    server = grpc.aio.server()
    rag_service_pb2_grpc.add_RagEngineServicer_to_server(servicer, server)

    bind_addr = f"{host}:{port}"
    server.add_insecure_port(bind_addr)
    logger.info("Server configured to bind on %s", bind_addr)
    return server


async def serve() -> None:
    port = int(os.getenv("PORT", "50051"))
    server = await create_server(port=port)
    await server.start()
    logger.info("Sentinel-RAG gRPC Server successfully started on port %d", port)
    await server.wait_for_termination()


if __name__ == "__main__":
    asyncio.run(serve())

