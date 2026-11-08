package com.sentinel.rag.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.gate.QualityGateResult;
import com.sentinel.rag.gate.QualityGateThresholdsConfig;
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
import java.util.*;

/**
 * Generates and posts/updates Markdown evaluation reports on pull requests per Plan §3.6.
 * Uses comment marker `<!-- sentinel-rag-report -->` for idempotent in-place comment editing.
 */
@Service
public class PrCommentService {

    private static final Logger log = LoggerFactory.getLogger(PrCommentService.class);
    public static final String COMMENT_MARKER = "<!-- sentinel-rag-report -->";

    private final ObjectMapper objectMapper;
    private final String githubToken;
    private final String defaultRepo;
    private final QualityGateThresholdsConfig thresholds;
    private final HttpClient httpClient;

    public PrCommentService(
            ObjectMapper objectMapper,
            QualityGateThresholdsConfig thresholds,
            @Value("${sentinel.github.token:}") String githubToken,
            @Value("${sentinel.github.default-repo:GodXSpell/SentinentRAG}") String defaultRepo
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.thresholds = thresholds;
        this.githubToken = githubToken != null ? githubToken.trim() : "";
        this.defaultRepo = defaultRepo;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void postOrUpdateReport(
            CiBuildRun buildRun,
            QualityGateResult gateResult,
            String canaryInfo,
            long p95LatencyMs,
            boolean allCached
    ) {
        if (githubToken.isBlank() || buildRun.getPullRequestId() == null) {
            log.info("Skipping PR comment (no token or not a PR run for commit {})", buildRun.getCommitSha());
            return;
        }

        String reportBody = generateMarkdownReport(buildRun, gateResult, canaryInfo, p95LatencyMs, allCached);
        int prNumber = buildRun.getPullRequestId();
        String repo = defaultRepo;

        try {
            Long existingCommentId = findExistingCommentId(repo, prNumber);
            if (existingCommentId != null) {
                updateComment(repo, existingCommentId, reportBody);
            } else {
                createComment(repo, prNumber, reportBody);
            }
        } catch (Exception e) {
            log.error("Failed to post/update PR comment on {}#{}: {}", repo, prNumber, e.getMessage());
        }
    }

    public String generateMarkdownReport(
            CiBuildRun buildRun,
            QualityGateResult gateResult,
            String canaryInfo,
            long p95LatencyMs,
            boolean allCached
    ) {
        boolean passed = buildRun.getStatus() == BuildStatus.PASSED;
        String statusEmoji = passed ? "✅ **PASSED**" : "❌ **FAILED**";

        String rawSha = buildRun.getCommitSha() != null ? buildRun.getCommitSha() : "unknown";
        String shortSha = rawSha.substring(0, Math.min(7, rawSha.length()));
        String branch = buildRun.getBranchName() != null ? buildRun.getBranchName() : "unknown";

        StringBuilder sb = new StringBuilder();
        sb.append(COMMENT_MARKER).append("\n");
        sb.append("### 🛡️ Sentinel-RAG Evaluation Gate: ").append(statusEmoji).append("\n\n");
        sb.append("**Commit:** `").append(shortSha).append("` | ");
        sb.append("**Branch:** `").append(branch).append("` | ");
        sb.append("**Total Duration:** ").append(buildRun.getExecutionDurationMs() != null ? buildRun.getExecutionDurationMs() : 0).append(" ms\n\n");

        sb.append("| Quality Metric | Required Floor | Observed Score | Status |\n");
        sb.append("| :--- | :--- | :--- | :--- |\n");

        // Faithfulness
        float faith = buildRun.getMeanFaithfulness() != null ? buildRun.getMeanFaithfulness() : 0f;
        boolean faithPass = faith >= thresholds.faithfulnessFloor();
        sb.append(String.format("| **Faithfulness** | ≥ %.2f | `%.3f` | %s |\n",
                thresholds.faithfulnessFloor(), faith, faithPass ? "PASS ✅" : "FAIL ❌"));

        // Context Precision
        float prec = buildRun.getMeanPrecision() != null ? buildRun.getMeanPrecision() : 0f;
        boolean precPass = prec >= thresholds.contextPrecisionFloor();
        sb.append(String.format("| **Context Precision (AP@K)** | ≥ %.2f | `%.3f` | %s |\n",
                thresholds.contextPrecisionFloor(), prec, precPass ? "PASS ✅" : "FAIL ❌"));

        // Refusal Accuracy
        if (buildRun.getRefusalAccuracy() != null) {
            float refAcc = buildRun.getRefusalAccuracy();
            boolean refPass = refAcc >= thresholds.refusalAccuracyFloor();
            sb.append(String.format("| **Refusal Accuracy** | ≥ %.0f%% | `%.1f%%` | %s |\n",
                    thresholds.refusalAccuracyFloor() * 100, refAcc * 100, refPass ? "PASS ✅" : "FAIL ❌"));
        } else {
            sb.append("| **Refusal Accuracy** | - | `N/A (no refusal cases)` | SKIP ⚪ |\n");
        }

        // Latency
        if (allCached) {
            sb.append(String.format("| **Latency (p95 non-cached)** | ≤ %d ms | `n/a — all cached` | PASS ⚡ |\n",
                    thresholds.latencyP95BudgetMs()));
        } else {
            boolean latPass = p95LatencyMs <= thresholds.latencyP95BudgetMs();
            sb.append(String.format("| **Latency (p95 non-cached)** | ≤ %d ms | `%d ms` | %s |\n",
                    thresholds.latencyP95BudgetMs(), p95LatencyMs, latPass ? "PASS ⚡" : "FAIL ❌"));
        }

        // Cache Hit Rate
        float hitRate = buildRun.getCacheHitRate() != null ? buildRun.getCacheHitRate() : 0f;
        sb.append(String.format("| **Cache Hit Rate** | - | `%.1f%%` | 🚀 |\n", hitRate * 100));

        if (!gateResult.passed() && !gateResult.violations().isEmpty()) {
            sb.append("\n#### ⚠️ Quality Gate Violations:\n");
            for (var v : gateResult.violations()) {
                sb.append("- **").append(v.checkName()).append("**: ").append(v.description()).append("\n");
            }
        }

        if (canaryInfo != null && !canaryInfo.isBlank()) {
            sb.append("\n> ℹ️ *").append(canaryInfo).append("*\n");
        }

        return sb.toString();
    }

    private Long findExistingCommentId(String repo, int prNumber) throws Exception {
        String url = String.format("https://api.github.com/repos/%s/issues/%d/comments", repo, prNumber);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        List<Map<String, Object>> comments = objectMapper.readValue(
                response.body(), new TypeReference<List<Map<String, Object>>>() {}
        );

        for (Map<String, Object> comment : comments) {
            String body = (String) comment.get("body");
            if (body != null && body.contains(COMMENT_MARKER)) {
                Object idObj = comment.get("id");
                if (idObj instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return null;
    }

    private void createComment(String repo, int prNumber, String body) throws Exception {
        String url = String.format("https://api.github.com/repos/%s/issues/%d/comments", repo, prNumber);
        Map<String, String> payload = Map.of("body", body);
        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("Created quality gate comment on PR {}#{}", repo, prNumber);
        } else {
            log.warn("Failed to create PR comment, HTTP {}: {}", response.statusCode(), response.body());
        }
    }

    private void updateComment(String repo, long commentId, String body) throws Exception {
        String url = String.format("https://api.github.com/repos/%s/issues/comments/%d", repo, commentId);
        Map<String, String> payload = Map.of("body", body);
        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("Updated existing quality gate comment ID {} on {}", commentId, repo);
        } else {
            log.warn("Failed to update PR comment ID {}, HTTP {}: {}", commentId, response.statusCode(), response.body());
        }
    }
}

