package com.sentinel.rag.gate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes p95 latency from a set of individual query execution times. Kept separate
 * from QualityGateService so the percentile math — the part most likely to have an
 * off-by-one error — has its own focused unit tests independent of threshold logic.
 */
public final class LatencyPercentileCalculator {

    private LatencyPercentileCalculator() {
    }

    /**
     * Nearest-rank method: sort ascending, take the value at index
     * ceil(0.95 * n) - 1. For n=20 that's index 18 (0-based) — the 19th value —
     * meaning 95% of samples are at or below it. This is the standard, simplest
     * correct definition of p95; more elaborate interpolated methods exist but add
     * complexity this system doesn't need.
     *
     * Returns 0 for an empty list rather than throwing — an empty build (zero test
     * cases actually ran) has no meaningful latency figure, and 0 will trivially pass
     * any latency threshold rather than crashing the whole quality gate over it.
     */
    public static long computeP95(List<Long> executionTimesMs) {
        if (executionTimesMs == null || executionTimesMs.isEmpty()) {
            return 0L;
        }

        List<Long> sorted = new ArrayList<>(executionTimesMs);
        Collections.sort(sorted);

        int rank = (int) Math.ceil(0.95 * sorted.size());
        int index = Math.max(0, rank - 1); // guard against rank=0 on very small lists

        return sorted.get(index);
    }
}