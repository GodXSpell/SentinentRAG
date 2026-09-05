package com.sentinel.rag.orchestration;

import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.grpc.GrpcEvaluationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fires EvaluationOrchestrationService.runEvaluation asynchronously so the webhook
 * handler can return its HTTP response to GitHub immediately, rather than blocking on
 * the full evaluation (including the gRPC round-trip to the Python plane).
 *
 * This is a SEPARATE bean from EvaluationOrchestrationService, not just a method on it,
 * because Spring's @Async works via a dynamic proxy wrapping the bean — calling an
 * @Async method from WITHIN the same class (self-invocation) bypasses that proxy
 * entirely and runs synchronously with no warning. Keeping the @Async entry point on
 * its own bean, called from WebhookIngestionService (a third, different bean), sidesteps
 * that trap structurally rather than relying on remembering not to self-invoke.
 */
@Component
public class EvaluationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EvaluationDispatcher.class);

    private final EvaluationOrchestrationService orchestrationService;

    public EvaluationDispatcher(EvaluationOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    /**
     * Fire-and-forget from the caller's perspective: returns void, not a Future, since
     * WebhookIngestionService has nothing further to do with the result — the outcome
     * lands in the database via runEvaluation's own CiBuildRun update, not via a return
     * value. Errors are caught and logged here rather than propagated, since there is no
     * caller left waiting to handle them by the time this runs on its own thread.
     */
    @Async
    public void dispatchEvaluation(CiBuildRun buildRun, UUID suiteId) {
        try {
            orchestrationService.runEvaluation(buildRun, suiteId);
        } catch (GrpcEvaluationException e) {
            // The build stays at RUNNING in the database — see EvaluationOrchestrationService's
            // javadoc note about a future ERRORED status to make this state visible/queryable
            // instead of only discoverable via logs.
            log.error("Evaluation failed for build {}: {}", buildRun.getBuildId(), e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during evaluation for build {}", buildRun.getBuildId(), e);
        }
    }
}