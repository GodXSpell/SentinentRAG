package com.sentinel.rag.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.github.model.GitHubWebhookPayload;
import com.sentinel.rag.orchestration.EvaluationDispatcher;
import com.sentinel.rag.repository.CiBuildRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Entry point for FR-1.1 and Plan §3.2.
 * Enforces signature verification, event filtering, fork PR rejection,
 * delivery-ID idempotency, and resolves active PRs for push events.
 */
@Service
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);
    private static final Set<String> SUPPORTED_PR_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final GitHubWebhookVerifier verifier;
    private final ObjectMapper objectMapper;
    private final CiBuildRunRepository ciBuildRunRepository;
    private final EvaluationDispatcher evaluationDispatcher;
    private final String webhookSecret;
    private final UUID defaultSuiteId;
    private final String githubToken;
    private final String defaultRepo;
    private final HttpClient httpClient;

    @Autowired
    public WebhookIngestionService(
            GitHubWebhookVerifier verifier,
            ObjectMapper objectMapper,
            CiBuildRunRepository ciBuildRunRepository,
            EvaluationDispatcher evaluationDispatcher,
            @Value("${sentinel.github.webhook-secret}") String webhookSecret,
            @Value("${sentinel.eval.default-suite-id}") String defaultSuiteId,
            @Value("${sentinel.github.token:}") String githubToken,
            @Value("${sentinel.github.default-repo:GodXSpell/SentinentRAG}") String defaultRepo) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.ciBuildRunRepository = ciBuildRunRepository;
        this.evaluationDispatcher = evaluationDispatcher;
        this.webhookSecret = webhookSecret;
        this.defaultSuiteId = UUID.fromString(defaultSuiteId);
        this.githubToken = githubToken != null ? githubToken.trim() : "";
        this.defaultRepo = defaultRepo;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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

        String branch = extractBranchName(payload.getRef());
        Integer prNumber = resolvePrForBranch(branch);

        CiBuildRun run = new CiBuildRun();
        run.setCommitSha(payload.getAfterCommitSha());
        run.setBranchName(branch);
        run.setPullRequestId(prNumber);
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
        if (!SUPPORTED_PR_ACTIONS.contains(payload.getAction()) || payload.getPullRequest() == null) {
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

    /**
     * Resolves the active Pull Request number for a branch:
     * 1. Fast lookup from local database history.
     * 2. Fallback query to GitHub API for open PRs matching the branch.
     */
    private Integer resolvePrForBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return null;
        }

        // 1. Fast lookup from database history
        Optional<CiBuildRun> cachedRun = ciBuildRunRepository
                .findFirstByBranchNameAndPullRequestIdIsNotNullOrderByCreatedAtDesc(branch);
        if (cachedRun.isPresent()) {
            Integer prId = cachedRun.get().getPullRequestId();
            log.info("Resolved PR #{} for branch '{}' from database history", prId, branch);
            return prId;
        }

        // 2. Query GitHub API for open pull request matching this branch
        if (githubToken != null && !githubToken.isBlank()) {
            try {
                String owner = defaultRepo.contains("/") ? defaultRepo.split("/")[0] : defaultRepo;
                String url = String.format("https://api.github.com/repos/%s/pulls?head=%s:%s&state=open",
                        defaultRepo, owner, branch);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + githubToken)
                        .header("Accept", "application/vnd.github.v3+json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode array = objectMapper.readTree(response.body());
                    if (array.isArray() && !array.isEmpty()) {
                        int prNum = array.get(0).get("number").asInt();
                        log.info("Resolved open PR #{} for branch '{}' via GitHub API", prNum, branch);
                        return prNum;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not query GitHub API for branch '{}' PR: {}", branch, e.getMessage());
            }
        }

        return null;
    }

    private String extractBranchName(String ref) {
        if (ref == null)
            return null;
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }
}
