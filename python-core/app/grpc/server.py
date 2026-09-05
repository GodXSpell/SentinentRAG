from concurrent import futures

import grpc

from app.grpc.generated import rag_service_pb2
from app.grpc.generated import rag_service_pb2_grpc


class RagEngineService(rag_service_pb2_grpc.RagEngineServicer):

    def RunEvaluation(self, request, context):
        print(
            f"Received evaluation request: "
            f"suite_id={request.suite_id}, "
            f"test_cases={len(request.test_cases)}, "
            f"force_bypass_cache={request.force_bypass_cache}"
        )

        results = []

        for test_case in request.test_cases:
            print(f"Evaluating test case: {test_case.test_id}")

            result = rag_service_pb2.TestResult(
                test_id=test_case.test_id,
                faithfulness_score=0.0,
                context_precision_score=0.0,
                answer_relevancy_score=0.0,
                execution_time_ms=0,
                was_cached=False,
                generated_answer=""
            )

            results.append(result)

        return rag_service_pb2.EvalBatchResponse(
            suite_id=request.suite_id,
            results=results,
            mean_faithfulness=0.0,
            mean_context_precision=0.0,
            total_duration_ms=0
        )


def serve():
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=10)
    )

    rag_service_pb2_grpc.add_RagEngineServicer_to_server(
        RagEngineService(),
        server
    )

    server.add_insecure_port("[::]:50051")

    server.start()

    print("Sentinel-RAG gRPC server started on port 50051")

    server.wait_for_termination()


if __name__ == "__main__":
    serve()