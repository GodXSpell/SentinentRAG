package com.sentinel.rag.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes commit status checks to GitHub per Plan §3.6.
 * Maps BuildStatus:
 * - RUNNING -> "pending"
 * - PASSED -> "success"
 * - FAILED -> "failure"
 * - SKIPPED -> "success" ("No test cases to evaluate")
 * - ERROR -> "error"
 */
@Service
public class GitHubStatusClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubStatusClient.class);
    private static final String CONTEXT = "sentinel-rag/quality-gate";

    private final ObjectMapper objectMapper;
    private final String githubToken;
    private final String defaultRepo;
    private final HttpClient httpClient;

    public GitHubStatusClient(
            ObjectMapper objectMapper,
            @Value("${sentinel.github.token:}") String githubToken,
            @Value("${sentinel.github.default-repo:GodXSpell/SentinentRAG}") String defaultRepo
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.githubToken = githubToken != null ? githubToken.trim() : "";
        this.defaultRepo = defaultRepo;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void updateStatus(CiBuildRun buildRun, String customDescription) {
        if (githubToken.isBlank()) {
            log.info("No GitHub token configured; skipping commit status publication for SHA {}", buildRun.getCommitSha());
            return;
        }

        String repo = defaultRepo;
        String sha = buildRun.getCommitSha();
        String state = mapStatusToState(buildRun.getStatus());
        String description = customDescription != null ? customDescription : buildDescription(buildRun);

        Map<String, String> body = new HashMap<>();
        body.put("state", state);
        body.put("context", CONTEXT);
        body.put("description", truncate(description, 140));

        try {
            String json = objectMapper.writeValueAsString(body);
            String url = String.format("https://api.github.com/repos/%s/statuses/%s", repo, sha);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Successfully published GitHub commit status '{}' for commit {}", state, sha);
            } else {
                log.warn("GitHub status publication returned HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Failed to publish GitHub commit status for SHA {}: {}", sha, e.getMessage());
        }
    }

    private String mapStatusToState(BuildStatus status) {
        if (status == null) return "pending";
        return switch (status) {
            case RUNNING -> "pending";
            case PASSED, SKIPPED -> "success";
            case FAILED -> "failure";
            case ERROR -> "error";
        };
    }

    private String buildDescription(CiBuildRun buildRun) {
        if (buildRun.getStatus() == BuildStatus.SKIPPED) {
            return "Skipped — test suite has no cases";
        }
        if (buildRun.getStatus() == BuildStatus.RUNNING) {
            return "Evaluating RAG pipeline quality gates...";
        }
        if (buildRun.getStatus() == BuildStatus.ERROR) {
            return "Evaluation encountered an unrecoverable error";
        }

        StringBuilder sb = new StringBuilder();
        if (buildRun.getMeanFaithfulness() != null) {
            sb.append(String.format("Faith: %.2f | ", buildRun.getMeanFaithfulness()));
        }
        if (buildRun.getMeanPrecision() != null) {
            sb.append(String.format("Prec: %.2f | ", buildRun.getMeanPrecision()));
        }
        if (buildRun.getRefusalAccuracy() != null) {
            sb.append(String.format("Refusal: %.0f%% | ", buildRun.getRefusalAccuracy() * 100));
        }
        if (buildRun.getCacheHitRate() != null) {
            sb.append(String.format("Cache: %.0f%%", buildRun.getCacheHitRate() * 100));
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}

