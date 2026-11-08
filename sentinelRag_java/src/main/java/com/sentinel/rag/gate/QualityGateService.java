package com.sentinel.rag.gate;

import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.entity.TestResultRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements FR-1.4 and Plan §1.3 / §2.8 / §3.4: evaluates a completed build's scores
 * against configurable quality thresholds and returns a pass/fail verdict with itemized violations.
 *
 * Latency Rule: p95 latency is evaluated strictly on non-cached executions.
 * If all cases are cached, latency check is skipped ("n/a — all cached").
 *
 * Refusal Rule: Refusal accuracy is checked only when the suite contains >= 1 declared refusal case.
 */
@Service
public class QualityGateService {

    private final QualityGateThresholdsConfig thresholds;

    public QualityGateService(QualityGateThresholdsConfig thresholds) {
        this.thresholds = thresholds;
    }

    public QualityGateResult evaluate(CiBuildRun buildRun, List<TestResultRecord> testResults) {
        List<QualityGateResult.Violation> violations = new ArrayList<>();

        if (thresholds.faithfulnessCheckEnabled() && buildRun.getMeanFaithfulness() != null) {
            double actual = buildRun.getMeanFaithfulness();
            double floor = thresholds.faithfulnessFloor();
            if (actual < floor) {
                violations.add(new QualityGateResult.Violation(
                        "faithfulness",
                        actual,
                        floor,
                        "Mean faithfulness %.3f is below required floor %.3f".formatted(actual, floor)
                ));
            }
        }

        if (thresholds.contextPrecisionCheckEnabled() && buildRun.getMeanPrecision() != null) {
            double actual = buildRun.getMeanPrecision();
            double floor = thresholds.contextPrecisionFloor();
            if (actual < floor) {
                violations.add(new QualityGateResult.Violation(
                        "context_precision",
                        actual,
                        floor,
                        "Mean context precision %.3f is below required floor %.3f".formatted(actual, floor)
                ));
            }
        }

        if (thresholds.refusalAccuracyCheckEnabled() && buildRun.getRefusalAccuracy() != null) {
            double actual = buildRun.getRefusalAccuracy();
            double floor = thresholds.refusalAccuracyFloor();
            if (actual < floor) {
                violations.add(new QualityGateResult.Violation(
                        "refusal_accuracy",
                        actual,
                        floor,
                        "Refusal accuracy %.3f is below required floor %.3f".formatted(actual, floor)
                ));
            }
        }

        if (thresholds.latencyCheckEnabled()) {
            List<Long> nonCachedTimes = testResults.stream()
                    .filter(r -> !Boolean.TRUE.equals(r.getWasCached()))
                    .map(TestResultRecord::getExecutionTimeMs)
                    .toList();

            // If all cases are cached, skip latency check (n/a - all cached)
            if (!nonCachedTimes.isEmpty()) {
                long p95 = LatencyPercentileCalculator.computeP95(nonCachedTimes);
                long budget = thresholds.latencyP95BudgetMs();
                if (p95 > budget) {
                    violations.add(new QualityGateResult.Violation(
                            "latency_p95",
                            p95,
                            budget,
                            "p95 latency %dms exceeds budget %dms".formatted(p95, budget)
                    ));
                }
            }
        }

        return new QualityGateResult(violations.isEmpty(), violations);
    }
}
