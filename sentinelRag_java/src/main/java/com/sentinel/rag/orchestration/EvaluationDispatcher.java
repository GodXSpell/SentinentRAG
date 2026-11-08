package com.sentinel.rag.orchestration;

import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.github.GitHubStatusClient;
import com.sentinel.rag.repository.CiBuildRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Fires EvaluationOrchestrationService.runEvaluation asynchronously so the webhook
 * handler returns 200 OK immediately. Handles failures by transitioning the build to
 * BuildStatus.ERROR and notifying GitHub status checks per Plan §3.5.
 */
@Component
public class EvaluationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EvaluationDispatcher.class);

    private final EvaluationOrchestrationService orchestrationService;
    private final CiBuildRunRepository ciBuildRunRepository;
    private final GitHubStatusClient gitHubStatusClient;

    public EvaluationDispatcher(
            EvaluationOrchestrationService orchestrationService,
            CiBuildRunRepository ciBuildRunRepository,
            GitHubStatusClient gitHubStatusClient
    ) {
        this.orchestrationService = Objects.requireNonNull(orchestrationService);
        this.ciBuildRunRepository = Objects.requireNonNull(ciBuildRunRepository);
        this.gitHubStatusClient = Objects.requireNonNull(gitHubStatusClient);
    }

    @Async
    public void dispatchEvaluation(CiBuildRun buildRun, UUID suiteId) {
        try {
            orchestrationService.runEvaluation(buildRun, suiteId);
        } catch (Exception e) {
            log.error("Evaluation failed for build {}: {}", buildRun.getBuildId(), e.getMessage(), e);
            try {
                buildRun.setStatus(BuildStatus.ERROR);
                CiBuildRun saved = ciBuildRunRepository.save(buildRun);
                gitHubStatusClient.updateStatus(saved, "Evaluation failed: " + e.getMessage());
            } catch (Exception dbError) {
                log.error("Could not persist ERROR status for build {}: {}", buildRun.getBuildId(), dbError.getMessage());
            }
        }
    }
}
