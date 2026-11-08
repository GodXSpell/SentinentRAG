"""
Sentinel-RAG Python inference/eval plane — gRPC server.

Implements the RagEngine service's RunEvaluation RPC per rag_service.proto.
Currently a STUB: it returns well-formed responses with placeholder scores so the
Java control plane's gRPC client, mapper, and orchestration logic can be proven
end-to-end before real evaluation logic (hybrid retrieval + reranking + generation +
NLI/Ragas scoring) is wired in.

WHAT'S STILL MISSING (not done in this file):
  - QueryRAG RPC is not implemented at all (RunEvaluation only)
  - No connection to the actual Week 1 RAG pipeline (retrieval, reranking, generation)
  - No real faithfulness/context-precision/answer-relevancy scoring
  - No semantic caching awareness on this side (Java already handles caching before
    calling this RPC at all, so cache_hash on each TestCase is currently unused here)
  - No error handling for malformed requests
"""

import time
from concurrent import futures

import grpc

from app.grpc.generated import rag_service_pb2
from app.grpc.generated import rag_service_pb2_grpc


class RagEngineService(rag_service_pb2_grpc.RagEngineServicer):

    def RunEvaluation(self, request, context):
        start_time = time.time()

        print(
            f"[RunEvaluation] suite_id={request.suite_id} "
            f"test_cases={len(request.test_cases)} "
            f"force_bypass_cache={request.force_bypass_cache}"
        )

        results = []
        for test_case in request.test_cases:
            print(
                f"  -> STUB evaluating test_id={test_case.test_id} "
                f"query={test_case.query!r}"
            )

            # STUB VALUES — replace this block with real evaluation once the RAG
            # pipeline (retrieval -> reranking -> generation -> NLI/Ragas scoring)
            # is wired in. Returning 0.0 scores is intentional for now: it makes the
            # quality gate on the Java side correctly and honestly FAIL (0.0 < 0.85
            # floor) rather than accidentally look like a real PASS from fabricated
            # good-looking numbers.
            result = rag_service_pb2.TestResult(
                test_id=test_case.test_id,
                faithfulness_score=0.0,
                context_precision_score=0.0,
                answer_relevancy_score=0.0,
                execution_time_ms=0,
                was_cached=False,
                generated_answer="[stub] not yet implemented",
            )
            results.append(result)

        total_duration_ms = int((time.time() - start_time) * 1000)

        # Aggregate means are computed here, honestly, from the (currently all-zero)
        # per-test results, rather than hardcoded separately — this keeps the response
        # internally consistent even once real per-test scores are added later, since
        # this averaging logic won't need to change.
        mean_faithfulness = (
            sum(r.faithfulness_score for r in results) / len(results)
            if results else 0.0
        )
        mean_context_precision = (
            sum(r.context_precision_score for r in results) / len(results)
            if results else 0.0
        )

        return rag_service_pb2.EvalBatchResponse(
            suite_id=request.suite_id,
            results=results,
            mean_faithfulness=mean_faithfulness,
            mean_context_precision=mean_context_precision,
            total_duration_ms=total_duration_ms,
        )


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    rag_service_pb2_grpc.add_RagEngineServicer_to_server(
        RagEngineService(), server
    )
    server.add_insecure_port("[::]:50051")
    server.start()
    print("Sentinel-RAG gRPC server (STUB mode) started on port 50051")
    server.wait_for_termination()


if __name__ == "__main__":
    serve()