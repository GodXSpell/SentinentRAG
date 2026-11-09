package com.sentinel.rag.gate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quality gate thresholds and per-check toggles (FR-1.4 and Plan §1.3), externalized so they're
 * tunable per-environment without a redeploy.
 */
@ConfigurationProperties(prefix = "sentinel.gate")
public record QualityGateThresholdsConfig(
        Double faithfulnessFloor,
        Double contextPrecisionFloor,
        Double refusalAccuracyFloor,
        Long latencyP95BudgetMs,
        Boolean faithfulnessCheckEnabled,
        Boolean contextPrecisionCheckEnabled,
        Boolean refusalAccuracyCheckEnabled,
        Boolean latencyCheckEnabled
) {
    public QualityGateThresholdsConfig {
        if (faithfulnessFloor == null) faithfulnessFloor = 0.85;
        if (contextPrecisionFloor == null) contextPrecisionFloor = 0.80;
        if (refusalAccuracyFloor == null) refusalAccuracyFloor = 1.0;
        if (latencyP95BudgetMs == null) latencyP95BudgetMs = 90000L;
        if (faithfulnessCheckEnabled == null) faithfulnessCheckEnabled = true;
        if (contextPrecisionCheckEnabled == null) contextPrecisionCheckEnabled = true;
        if (refusalAccuracyCheckEnabled == null) refusalAccuracyCheckEnabled = true;
        if (latencyCheckEnabled == null) latencyCheckEnabled = true;
    }
}
