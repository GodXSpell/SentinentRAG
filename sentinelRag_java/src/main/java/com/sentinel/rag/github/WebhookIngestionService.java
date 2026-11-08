package com.sentinel.rag.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.github.model.GitHubWebhookPayload;
import com.sentinel.rag.orchestration.EvaluationDispatcher;
import com.sentinel.rag.repository.CiBuildRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Entry point for FR-1.1 and Plan §3.2.
 * Enforces signature verification, event filtering, fork PR rejection,
 * and delivery-ID idempotency.
 */
@Service
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);

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

    public Optional<CiBuildRun> ingest(String rawPayload, String signatureHeader, String eventType) {
        return ingest(rawPayload, signatureHeader, eventType, null);
    }

    public Optional<CiBuildRun> ingest(String rawPayload, String signatureHeader, String eventType, String deliveryId) {
        if (!verifier.isValidSignature(rawPayload, signatureHeader, webhookSecret)) {
            throw new SecurityException("Invalid webhook signature");
        }

        if (deliveryId != null && ciBuildRunRepository.findByDeliveryId(deliveryId).isPresent()) {
            log.info("Duplicate delivery ID {}, skipping build dispatch", deliveryId);
            return Optional.empty();
        }

        GitHubWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawPayload, GitHubWebhookPayload.class);
        } catch (Exception e) {
            log.warn("Failed to parse validated webhook payload: {}", e.getMessage());
            return Optional.empty();
        }

        Optional<CiBuildRun> result = switch (eventType) {
            case "push" -> handlePush(payload, deliveryId);
            case "pull_request" -> handlePullRequest(payload, deliveryId);
            default -> Optional.empty();
        };

        result.ifPresent(buildRun -> evaluationDispatcher.dispatchEvaluation(buildRun, defaultSuiteId));
        return result;
    }

    private Optional<CiBuildRun> handlePush(GitHubWebhookPayload payload, String deliveryId) {
        if (payload.getAfterCommitSha() == null || payload.getRef() == null) {
            return Optional.empty();
        }

        CiBuildRun run = new CiBuildRun();
        run.setCommitSha(payload.getAfterCommitSha());
        run.setBranchName(extractBranchName(payload.getRef()));
        run.setPullRequestId(null);
        run.setStatus(BuildStatus.RUNNING);
        run.setDeliveryId(deliveryId);

        try {
            return Optional.of(ciBuildRunRepository.save(run));
        } catch (DataIntegrityViolationException e) {
            log.info("Race condition on delivery_id idempotency for push event: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<CiBuildRun> handlePullRequest(GitHubWebhookPayload payload, String deliveryId) {
        if (!"opened".equals(payload.getAction()) || payload.getPullRequest() == null) {
            return Optional.empty();
        }

        var pr = payload.getPullRequest();
        if (pr.isFork()) {
            log.info("Ignoring fork pull request #{} to protect Groq quota and avoid missing base SHA", pr.getNumber());
            return Optional.empty();
        }

        if (pr.getHead() == null || pr.getHead().getSha() == null) {
            return Optional.empty();
        }

        CiBuildRun run = new CiBuildRun();
        run.setCommitSha(pr.getHead().getSha());
        run.setBranchName(extractBranchName(pr.getHead().getRef()));
        run.setPullRequestId(pr.getNumber());
        run.setStatus(BuildStatus.RUNNING);
        run.setDeliveryId(deliveryId);

        try {
            return Optional.of(ciBuildRunRepository.save(run));
        } catch (DataIntegrityViolationException e) {
            log.info("Race condition on delivery_id idempotency for PR event: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractBranchName(String ref) {
        if (ref == null) return null;
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }
}
