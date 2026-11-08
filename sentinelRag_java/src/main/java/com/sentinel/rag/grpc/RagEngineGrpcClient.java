package com.sentinel.rag.grpc;

import com.sentinel.rag.grpc.Empty;
import com.sentinel.rag.grpc.EngineInfoResponse;
import com.sentinel.rag.grpc.EvalBatchRequest;
import com.sentinel.rag.grpc.EvalBatchResponse;
import com.sentinel.rag.grpc.PrepareIndexRequest;
import com.sentinel.rag.grpc.PrepareIndexResponse;
import com.sentinel.rag.grpc.RagEngineGrpc.RagEngineBlockingStub;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the generated RagEngine gRPC stub per Plan §3.4.
 * Enforces explicit call deadlines:
 * - GetEngineInfo: 10s
 * - PrepareIndex: 600s (configurable)
 * - RunEvaluation: 120s (configurable)
 */
@Component
public class RagEngineGrpcClient {

    private final RagEngineBlockingStub blockingStub;
    private final long indexTimeoutSeconds;
    private final long evaluationDeadlineSeconds;

    public RagEngineGrpcClient(
            RagEngineBlockingStub blockingStub,
            @Value("${sentinel.grpc.index-timeout-seconds:600}") long indexTimeoutSeconds,
            @Value("${sentinel.grpc.evaluation-deadline-seconds:120}") long evaluationDeadlineSeconds
    ) {
        this.blockingStub = Objects.requireNonNull(blockingStub, "blockingStub must not be null");
        this.indexTimeoutSeconds = indexTimeoutSeconds;
        this.evaluationDeadlineSeconds = evaluationDeadlineSeconds;
    }

    /**
     * Retrieves engine version and model IDs from the Python engine (Plan §1.2).
     */
    public EngineInfoResponse getEngineInfo() {
        try {
            return blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getEngineInfo(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            throw new GrpcEvaluationException("GetEngineInfo call failed: " + e.getStatus(), e);
        }
    }

    /**
     * Prepares and indexes corpus documents if index_hash alias does not yet exist.
     */
    public PrepareIndexResponse prepareIndex(PrepareIndexRequest request) {
        try {
            return blockingStub.withDeadlineAfter(indexTimeoutSeconds, TimeUnit.SECONDS)
                    .prepareIndex(request);
        } catch (StatusRuntimeException e) {
            throw new GrpcEvaluationException("PrepareIndex call failed: " + e.getStatus(), e);
        }
    }

    /**
     * Evaluates a batch of test cases over gRPC with a configurable deadline bound.
     */
    public EvalBatchResponse runEvaluation(EvalBatchRequest request) {
        try {
            return blockingStub.withDeadlineAfter(evaluationDeadlineSeconds, TimeUnit.SECONDS)
                    .runEvaluation(request);
        } catch (StatusRuntimeException e) {
            throw new GrpcEvaluationException("RunEvaluation call failed: " + e.getStatus(), e);
        }
    }
}
