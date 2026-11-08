package com.sentinel.rag.gate;

import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.entity.TestResultRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QualityGateServiceTest {

    private QualityGateThresholdsConfig thresholds;
    private QualityGateService service;

    @BeforeEach
    void setUp() {
        thresholds = new QualityGateThresholdsConfig(
                0.85, 0.80, 1.0, 800L,
                true, true, true, true
        );
        service = new QualityGateService(thresholds);
    }

    @Test
    void evaluate_passesWhenAllMetricsMeetFloors() {
        CiBuildRun run = new CiBuildRun();
        run.setMeanFaithfulness(0.92f);
        run.setMeanPrecision(0.88f);
        run.setRefusalAccuracy(1.0f);

        TestResultRecord r1 = new TestResultRecord();
        r1.setExecutionTimeMs(450L);
        r1.setWasCached(false);

        QualityGateResult result = service.evaluate(run, List.of(r1));
        assertTrue(result.passed());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void evaluate_failsWhenFaithfulnessBreachesFloor() {
        CiBuildRun run = new CiBuildRun();
        run.setMeanFaithfulness(0.79f); // Floor is 0.85
        run.setMeanPrecision(0.88f);
        run.setRefusalAccuracy(1.0f);

        QualityGateResult result = service.evaluate(run, List.of());
        assertFalse(result.passed());
        assertEquals(1, result.violations().size());
        assertEquals("faithfulness", result.violations().get(0).checkName());
    }

    @Test
    void evaluate_failsWhenRefusalAccuracyBreachesFloor() {
        CiBuildRun run = new CiBuildRun();
        run.setMeanFaithfulness(0.90f);
        run.setMeanPrecision(0.88f);
        run.setRefusalAccuracy(0.50f); // Floor is 1.0

        QualityGateResult result = service.evaluate(run, List.of());
        assertFalse(result.passed());
        assertTrue(result.violations().stream().anyMatch(v -> v.checkName().equals("refusal_accuracy")));
    }

    @Test
    void evaluate_skipsRefusalCheckWhenRefusalAccuracyIsNull() {
        CiBuildRun run = new CiBuildRun();
        run.setMeanFaithfulness(0.90f);
        run.setMeanPrecision(0.88f);
        run.setRefusalAccuracy(null); // Suite had no declared refusal cases

        QualityGateResult result = service.evaluate(run, List.of());
        assertTrue(result.passed());
    }

    @Test
    void evaluate_skipsLatencyCheckWhenAllResultsAreCached() {
        CiBuildRun run = new CiBuildRun();
        run.setMeanFaithfulness(0.90f);
        run.setMeanPrecision(0.88f);
        run.setRefusalAccuracy(1.0f);

        TestResultRecord cached1 = new TestResultRecord();
        cached1.setExecutionTimeMs(0L);
        cached1.setWasCached(true);

        TestResultRecord cached2 = new TestResultRecord();
        cached2.setExecutionTimeMs(0L);
        cached2.setWasCached(true);

        QualityGateResult result = service.evaluate(run, List.of(cached1, cached2));
        assertTrue(result.passed(), "Should pass without latency violation when all cases are cached");
    }

    @Test
    void evaluate_failsWhenNonCachedP95ExceedsBudget() {
        CiBuildRun run = new CiBuildRun();
        run.setMeanFaithfulness(0.90f);
        run.setMeanPrecision(0.88f);
        run.setRefusalAccuracy(1.0f);

        List<TestResultRecord> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TestResultRecord r = new TestResultRecord();
            r.setExecutionTimeMs(950L); // Exceeds 800ms budget
            r.setWasCached(false);
            records.add(r);
        }

        QualityGateResult result = service.evaluate(run, records);
        assertFalse(result.passed());
        assertTrue(result.violations().stream().anyMatch(v -> v.checkName().equals("latency_p95")));
    }
}
