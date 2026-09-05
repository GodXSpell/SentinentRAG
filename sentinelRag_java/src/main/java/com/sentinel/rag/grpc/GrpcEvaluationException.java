package com.sentinel.rag.grpc;

/**
 * Wraps any gRPC-layer failure (unreachable server, timeout, malformed response) so
 * callers of RagEngineGrpcClient depend on this one exception type instead of importing
 * io.grpc.StatusRuntimeException throughout the codebase. Unchecked, since a failed
 * evaluation call is an exceptional condition the caller (EvaluationOrchestrationService)
 * handles explicitly rather than being forced to declare in every method signature.
 */
public class GrpcEvaluationException extends RuntimeException {
    public GrpcEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}