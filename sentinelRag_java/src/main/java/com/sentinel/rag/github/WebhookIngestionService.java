package com.sentinel.rag.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.github.model.GitHubWebhookPayload;
import com.sentinel.rag.orchestration.EvaluationDispatcher;
import com.sentinel.rag.repository.CiBuildRunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Entry point for FR-1.1. Owns the security-critical order of operations:
 *   1. verify signature against the RAW body
 *   2. only then parse JSON
 *   3. only then decide whether this event even warrants a build
 *
 * Uses com.fasterxml.jackson.databind.ObjectMapper directly — available and
 * auto-configured as a bean because the pom explicitly depends on spring-boot-jackson2,
 * keeping Jackson 2 available alongside Spring Boot 4.1's new Jackson-3-by-default stack.
 *
 * Reordering steps 1 and 2 — parsing before verifying — would mean an attacker's
 * malformed or malicious payload gets fed to Jackson before you've established the
 * request actually came from GitHub. Verify first, always.
 *
 * DEFAULT SUITE ID: which suite runs for a given build is currently a single configurable
 * value (sentinel.eval.default-suite-id), not derived from the webhook payload itself
 * (e.g. a PR label). This is a deliberate placeholder — the entry point below
 * (dispatchEvaluation call) is the one seam that needs to change when real suite
 * selection (label-based, path-based, etc.) is built; nothing else in the pipeline
 * needs to know how the suite ID was chosen.
 */
@Service
public class WebhookIngestionService {

    private final GitHubWebhookVerifier verifier;
    private final ObjectMapper objectMapper;
    private final CiBuildRunRepository ciBuildRunRepository;
    private final EvaluationDispatcher evaluationDispatcher;
    private final String webhookSecret;
    private final UUID defaultSuiteId;

    public WebhookIngestionService(
            GitHubWebhookVerifier verifier,
            ObjectMapper objectMapper,
            CiBuildRunRepository ciBuildRunRepository,
            EvaluationDispatcher evaluationDispatcher,
            @Value("${sentinel.github.webhook-secret}") String webhookSecret,
            @Value("${sentinel.eval.default-suite-id}") String defaultSuiteId
    ) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.ciBuildRunRepository = ciBuildRunRepository;
        this.evaluationDispatcher = evaluationDispatcher;
        this.webhookSecret = webhookSecret;
        this.defaultSuiteId = UUID.fromString(defaultSuiteId);
    }

    /**
     * @return the created CiBuildRun if this event triggered a build, or empty if the
     *         signature was invalid or the event type/action isn't one we act on.
     * @throws SecurityException if the signature verification fails — the controller
     *         maps this to a 401, never leaking whether it was a bad signature vs. some
     *         other rejection reason.
     */
    public Optional<CiBuildRun> ingest(String rawPayload, String signatureHeader, String eventType) {
        if (!verifier.isValidSignature(rawPayload, signatureHeader, webhookSecret)) {
            throw new SecurityException("Invalid webhook signature");
        }

        GitHubWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawPayload, GitHubWebhookPayload.class);
        } catch (Exception e) {
            // A signature-valid-but-unparseable payload is unusual (would mean GitHub
            // itself sent malformed JSON, which shouldn't happen) — treat as a no-op
            // rather than a hard failure, since there's nothing safe to act on.
            return Optional.empty();
        }

        Optional<CiBuildRun> result = switch (eventType) {
            case "push" -> handlePush(payload);
            case "pull_request" -> handlePullRequest(payload);
            default -> Optional.empty(); // event type we don't act on (FR-1.1 scope)
        };

        // Fire evaluation asynchronously the moment a build row exists — the webhook
        // HTTP response returns to GitHub immediately regardless of how long the actual
        // evaluation (including the gRPC round-trip) takes.
        result.ifPresent(buildRun -> evaluationDispatcher.dispatchEvaluation(buildRun, defaultSuiteId));

        return result;
    }

    private Optional<CiBuildRun> handlePush(GitHubWebhookPayload payload) {
        if (payload.getAfterCommitSha() == null || payload.getRef() == null) {
            return Optional.empty();
        }

        CiBuildRun run = new CiBuildRun();
        run.setCommitSha(payload.getAfterCommitSha());
        run.setBranchName(extractBranchName(payload.getRef()));
        run.setPullRequestId(null);
        run.setStatus(BuildStatus.RUNNING);
        // meanFaithfulness, meanPrecision, cacheHitRate, executionDurationMs stay null —
        // this is exactly the RUNNING state V3__allow_running_build_state.sql exists for.

        return Optional.of(ciBuildRunRepository.save(run));
    }

    private Optional<CiBuildRun> handlePullRequest(GitHubWebhookPayload payload) {
        // Only "opened" triggers a build (FR-1.1) — synchronize/closed/reopened etc.
        // are ignored for now. Widen this set deliberately later if you want re-runs
        // on new commits pushed to an existing PR (the "synchronize" action).
        if (!"opened".equals(payload.getAction()) || payload.getPullRequest() == null) {
            return Optional.empty();
        }

        var pr = payload.getPullRequest();
        if (pr.getHead() == null || pr.getHead().getSha() == null) {
            return Optional.empty();
        }

        CiBuildRun run = new CiBuildRun();
        run.setCommitSha(pr.getHead().getSha());
        run.setBranchName(extractBranchName(pr.getHead().getRef()));
        run.setPullRequestId(pr.getNumber());
        run.setStatus(BuildStatus.RUNNING);

        return Optional.of(ciBuildRunRepository.save(run));
    }

    /** "refs/heads/main" -> "main". Push events send the full ref; PR heads send just the name. */
    private String extractBranchName(String ref) {
        if (ref == null) return null;
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }
}