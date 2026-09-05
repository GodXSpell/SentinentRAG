package com.sentinel.rag.gate;

import java.util.List;

/**
 * Result of running QualityGateService against a completed build. Carries not just a
 * boolean but the specific violations, since FR-1.5's PR comment bot needs to report
 * which threshold(s) failed and by how much — "faithfulness 0.79 < required 0.85" is
 * useful to a developer; a bare "FAILED" is not.
 */
public record QualityGateResult(
        boolean passed,
        List<Violation> violations
) {
    public record Violation(
            String checkName,
            double actualValue,
            double thresholdValue,
            String description
    ) {
    }
}