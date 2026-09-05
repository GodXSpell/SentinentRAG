package com.sentinel.rag.gate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quality gate thresholds and per-check toggles (FR-1.4), externalized so they're
 * tunable per-environment without a redeploy.
 *
 * Convention-over-configuration: every field already has the PRD's real default value
 * here in the record itself. An application.yml with NO "sentinel.gate.*" keys at all
 * still produces a fully working, fully-enabled quality gate using these exact numbers —
 * config only needs to be touched to override a specific value, never to "turn the
 * feature on" in the first place.
 *
 * Bind under prefix "sentinel.gate", e.g.:
 *   sentinel:
 *     gate:
 *       faithfulness-floor: 0.90   # overrides just this one value
 *       # everything else silently keeps its default below
 */
@ConfigurationProperties(prefix = "sentinel.gate")
public record QualityGateThresholdsConfig(
        Double faithfulnessFloor,
        Double contextPrecisionFloor,
        Long latencyP95BudgetMs,
        Boolean faithfulnessCheckEnabled,
        Boolean contextPrecisionCheckEnabled,
        Boolean latencyCheckEnabled
) {
    // Compact constructor: fills in any null field (i.e. not set in application.yml)
    // with the PRD default, rather than requiring every environment to fully specify
    // every key. This is what makes "empty config = fully working defaults" actually
    // true instead of just documented.
    public QualityGateThresholdsConfig {
        if (faithfulnessFloor == null) faithfulnessFloor = 0.85;
        if (contextPrecisionFloor == null) contextPrecisionFloor = 0.80;
        if (latencyP95BudgetMs == null) latencyP95BudgetMs = 800L;
        if (faithfulnessCheckEnabled == null) faithfulnessCheckEnabled = true;
        if (contextPrecisionCheckEnabled == null) contextPrecisionCheckEnabled = true;
        if (latencyCheckEnabled == null) latencyCheckEnabled = true;
    }
}