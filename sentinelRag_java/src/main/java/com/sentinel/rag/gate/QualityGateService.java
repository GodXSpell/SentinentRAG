package com.sentinel.rag.gate;

import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.entity.TestResultRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements FR-1.4: evaluates a completed build's scores against configurable quality
 * thresholds and returns a pass/fail verdict with itemized violations.
 *
 * Deliberately a pure function — evaluate() takes everything it needs as arguments and
 * returns a result with no side effects (no DB writes, no gRPC calls, no logging as a
 * load-bearing part of its behavior). This is what makes it trivial to unit test: feed
 * it a CiBuildRun + TestResultRecord list + thresholds, assert on the QualityGateResult,
 * no mocking required.
 *
 * Reliability principle: this checks the FINAL aggregate scores exactly the same way
 * whether or not the Python plane's self-correction retry loop already ran. There is no
 * softened bar for a build that only passed after a retry — the same 0.85/0.80/800ms
 * floors apply regardless of how the scores were arrived at. Reliability takes priority
 * over a lower failure rate.
 */
@Service
public class QualityGateService {

    private final QualityGateThresholdsConfig thresholds;

    public QualityGateService(QualityGateThresholdsConfig thresholds) {
        this.thresholds = thresholds;
    }

    public QualityGateResult evaluate(CiBuildRun buildRun, List<TestResultRecord> testResults) {
        List<QualityGateResult.Violation> violations = new ArrayList<>();

        if (thresholds.faithfulnessCheckEnabled()) {
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

        if (thresholds.contextPrecisionCheckEnabled()) {
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

        if (thresholds.latencyCheckEnabled()) {
            List<Long> executionTimes = testResults.stream()
                    .map(TestResultRecord::getExecutionTimeMs)
                    .toList();
            long p95 = LatencyPercentileCalculator.computeP95(executionTimes);
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

        return new QualityGateResult(violations.isEmpty(), violations);
    }
}