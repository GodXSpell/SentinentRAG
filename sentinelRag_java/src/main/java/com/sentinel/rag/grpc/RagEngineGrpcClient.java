package com.sentinel.rag.grpc;

import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over the generated RagEngine gRPC stub. Callers (EvaluationOrchestrationService)
 * never touch proto types directly — only this class and RagEvaluationMapper know about
 * EvalBatchRequest/EvalBatchResponse. This keeps a proto contract change (or eventually a
 * gRPC-to-REST swap, however unlikely) contained to this one class instead of rippling
 * through orchestration logic.
 *
 * NOTE: there is no Python gRPC server running yet. Calls made before that server exists
 * will fail with a gRPC UNAVAILABLE StatusRuntimeException (connection refused) — this is
 * the correct, honest failure mode for now. EvaluationOrchestrationService is responsible
 * for catching that and deciding how to handle it (currently: propagate as a build failure,
 * not silently swallow it).
 */
@Component
public class RagEngineGrpcClient {

    // The generated blocking stub, wired via Spring Boot 4.1's gRPC client starter
    // (@ImportGrpcClients on Application, or an explicit @Bean — see application.yml
    // for the target host:port this channel points at). Injected here rather than
    // constructed manually so channel lifecycle/config stays Spring-managed.
    private final RagEngineGrpc.RagEngineBlockingStub blockingStub;

    public RagEngineGrpcClient(RagEngineGrpc.RagEngineBlockingStub blockingStub) {
        this.blockingStub = blockingStub;
    }

    /**
     * Calls the Python plane's RunEvaluation RPC synchronously (blocking stub — acceptable
     * here since this runs inside an already-async webhook-triggered build process, not
     * on a request thread the user is waiting on).
     *
     * @throws GrpcEvaluationException if the call fails for any reason (server unreachable,
     *         timeout, malformed response) — wraps the raw gRPC exception so callers don't
     *         need to know about io.grpc.StatusRuntimeException specifically.
     */
    public EvalBatchResponse runEvaluation(EvalBatchRequest request) {
        try {
            return blockingStub.runEvaluation(request);
        } catch (StatusRuntimeException e) {
            throw new GrpcEvaluationException(
                    "RunEvaluation call failed: " + e.getStatus(), e);
        }
    }
}