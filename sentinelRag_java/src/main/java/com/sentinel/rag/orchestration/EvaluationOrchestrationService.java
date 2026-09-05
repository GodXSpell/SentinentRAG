package com.sentinel.rag.orchestration;

import com.sentinel.rag.cache.ConfigParams;
import com.sentinel.rag.cache.SemanticCacheService;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.entity.EvalCache;
import com.sentinel.rag.entity.TestResultRecord;
import com.sentinel.rag.gate.QualityGateResult;
import com.sentinel.rag.gate.QualityGateService;
import com.sentinel.rag.grpc.EvalBatchRequest;
import com.sentinel.rag.grpc.EvalBatchResponse;
import com.sentinel.rag.grpc.GrpcEvaluationException;
import com.sentinel.rag.grpc.RagEngineGrpcClient;
import com.sentinel.rag.grpc.RagEvaluationMapper;
import com.sentinel.rag.grpc.TestResult;
import com.sentinel.rag.repository.CiBuildRunRepository;
import com.sentinel.rag.repository.TestCaseRepository;
import com.sentinel.rag.repository.TestResultRecordRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The connective tissue tying together every piece built so far: loads a suite's test
 * cases, checks the semantic cache per case, dispatches cache misses to the Python plane
 * via gRPC, persists per-test results, aggregates them, runs the quality gate, and updates
 * the CiBuildRun row from RUNNING to PASSED/FAILED.
 *
 * PROMPT TEMPLATE — not yet a real concept in this codebase (no PromptTemplate entity or
 * config exists). Using a single hardcoded default here as a placeholder is intentional:
 * once real prompt template management exists (versioned, configurable, tied to the
 * suite or test case), this is the one place that needs to change to source it properly
 * instead of every call site that computes a cache hash.
 */
@Service
public class EvaluationOrchestrationService {

    // Placeholder until real prompt template management exists — see class javadoc.
    private static final String DEFAULT_PROMPT_TEMPLATE = "default-v1";

    private final TestCaseRepository testCaseRepository;
    private final CiBuildRunRepository ciBuildRunRepository;
    private final TestResultRecordRepository testResultRecordRepository;
    private final SemanticCacheService semanticCacheService;
    private final RagEngineGrpcClient grpcClient;
    private final RagEvaluationMapper mapper;
    private final QualityGateService qualityGateService;
    private final String evaluatorModel;
    private final String rerankerModel;
    private final int retrievalTopK;

    public EvaluationOrchestrationService(
            TestCaseRepository testCaseRepository,
            CiBuildRunRepository ciBuildRunRepository,
            TestResultRecordRepository testResultRecordRepository,
            SemanticCacheService semanticCacheService,
            RagEngineGrpcClient grpcClient,
            RagEvaluationMapper mapper,
            QualityGateService qualityGateService,
            @Value("${sentinel.eval.evaluator-model:local-nli-v1}") String evaluatorModel,
            @Value("${sentinel.eval.reranker-model:ms-marco-MiniLM-L-6-v2}") String rerankerModel,
            @Value("${sentinel.eval.retrieval-top-k:20}") int retrievalTopK
    ) {
        this.testCaseRepository = testCaseRepository;
        this.ciBuildRunRepository = ciBuildRunRepository;
        this.testResultRecordRepository = testResultRecordRepository;
        this.semanticCacheService = semanticCacheService;
        this.grpcClient = grpcClient;
        this.mapper = mapper;
        this.qualityGateService = qualityGateService;
        this.evaluatorModel = evaluatorModel;
        this.rerankerModel = rerankerModel;
        this.retrievalTopK = retrievalTopK;
    }

    /**
     * Runs a full evaluation cycle for the given suite against an already-RUNNING build,
     * ending with the build row updated to PASSED or FAILED.
     *
     * @throws GrpcEvaluationException if the gRPC call fails — propagated rather than
     *         swallowed, since a build that can't actually run its evaluation has no
     *         honest PASSED/FAILED verdict to give. Caller (webhook flow) decides how
     *         to surface this (e.g. leave the build stuck at RUNNING, or a future
     *         ERRORED status — see note in evaluate()).
     */
    public CiBuildRun runEvaluation(CiBuildRun buildRun, UUID suiteId) {
        List<com.sentinel.rag.entity.TestCase> testCases =
                testCaseRepository.findBySuite_SuiteId(suiteId);

        // A suite with zero test cases has nothing for the quality gate to evaluate.
        // Forcing PASSED/FAILED here would be misleading either direction — see
        // BuildStatus.SKIPPED's javadoc for why this needed its own distinct status
        // rather than defaulting to one of the other two.
        if (testCases.isEmpty()) {
            buildRun.setMeanFaithfulness(0f);
            buildRun.setMeanPrecision(0f);
            buildRun.setCacheHitRate(0f);
            buildRun.setExecutionDurationMs(0L);
            buildRun.setStatus(BuildStatus.SKIPPED);
            return ciBuildRunRepository.save(buildRun);
        }

        ConfigParams configParams = new ConfigParams(evaluatorModel, rerankerModel, retrievalTopK);

        // Per-test cache lookup, BEFORE building the gRPC request — cache hits never
        // need to leave this process at all, which is the entire point of FR-1.3.
        List<com.sentinel.rag.entity.TestCase> cacheMisses = new ArrayList<>();
        List<String> missCacheHashes = new ArrayList<>();
        List<TestResultRecord> results = new ArrayList<>();
        int cacheHits = 0;

        for (com.sentinel.rag.entity.TestCase testCase : testCases) {
            String cacheHash = semanticCacheService.computeHash(
                    DEFAULT_PROMPT_TEMPLATE, configParams, testCase.getQuery(),
                    testCase.getExpectedGroundTruth());

            Optional<EvalCache> cached = semanticCacheService.lookup(cacheHash);
            if (cached.isPresent()) {
                cacheHits++;
                results.add(fromCacheHit(cached.get(), testCase, buildRun));
            } else {
                cacheMisses.add(testCase);
                missCacheHashes.add(cacheHash);
            }
        }

        // Only build and send a gRPC request if there's actually something to evaluate —
        // an all-cache-hit build should never touch the network at all.
        if (!cacheMisses.isEmpty()) {
            List<String> promptTemplates = cacheMisses.stream()
                    .map(tc -> DEFAULT_PROMPT_TEMPLATE)
                    .toList();

            EvalBatchRequest request = mapper.toEvalBatchRequest(
                    suiteId, cacheMisses, promptTemplates, missCacheHashes, false);

            EvalBatchResponse response = grpcClient.runEvaluation(request);

            for (TestResult protoResult : response.getResultsList()) {
                TestResultRecord record = mapper.toTestResultRecord(protoResult, buildRun);
                results.add(record);

                // Populate the cache with this freshly computed result so the NEXT run
                // over an unchanged test case hits the sub-5ms path instead of gRPC again.
                String matchingHash = missCacheHashes.get(
                        indexOfTestId(cacheMisses, protoResult.getTestId()));
                semanticCacheService.store(
                        matchingHash,
                        protoResult.getGeneratedAnswer(),
                        protoResult.getFaithfulnessScore(),
                        protoResult.getContextPrecisionScore(),
                        protoResult.getAnswerRelevancyScore(),
                        evaluatorModel);
            }
        }

        testResultRecordRepository.saveAll(results);

        float cacheHitRate = testCases.isEmpty() ? 0f : (float) cacheHits / testCases.size();
        applyAggregates(buildRun, results, cacheHitRate);

        QualityGateResult gateResult = qualityGateService.evaluate(buildRun, results);
        buildRun.setStatus(gateResult.passed() ? BuildStatus.PASSED : BuildStatus.FAILED);

        return ciBuildRunRepository.save(buildRun);
    }

    /** Builds a TestResultRecord directly from a cache hit, skipping gRPC entirely. */
    private TestResultRecord fromCacheHit(EvalCache cached,
                                          com.sentinel.rag.entity.TestCase testCase,
                                          CiBuildRun buildRun) {
        TestResultRecord record = new TestResultRecord();
        record.setBuildRun(buildRun);
        record.setTestId(testCase.getTestId());
        record.setFaithfulnessScore(cached.getFaithfulnessScore());
        record.setContextPrecisionScore(cached.getContextPrecisionScore());
        record.setAnswerRelevancyScore(cached.getAnswerRelevancyScore());
        record.setExecutionTimeMs(0L); // cache hits are sub-5ms; treated as 0 for p95 purposes
        record.setWasCached(true);
        record.setGeneratedAnswer(cached.getGeneratedAnswer());
        return record;
    }

    /** Aggregates mean faithfulness/precision and total duration across all results. */
    private void applyAggregates(CiBuildRun buildRun, List<TestResultRecord> results,
                                 float cacheHitRate) {
        if (results.isEmpty()) {
            buildRun.setMeanFaithfulness(0f);
            buildRun.setMeanPrecision(0f);
            buildRun.setCacheHitRate(cacheHitRate);
            buildRun.setExecutionDurationMs(0L);
            return;
        }

        double totalFaithfulness = 0;
        double totalPrecision = 0;
        long totalDurationMs = 0;

        for (TestResultRecord r : results) {
            totalFaithfulness += r.getFaithfulnessScore();
            totalPrecision += r.getContextPrecisionScore();
            totalDurationMs += r.getExecutionTimeMs();
        }

        buildRun.setMeanFaithfulness((float) (totalFaithfulness / results.size()));
        buildRun.setMeanPrecision((float) (totalPrecision / results.size()));
        buildRun.setCacheHitRate(cacheHitRate);
        buildRun.setExecutionDurationMs(totalDurationMs);
    }

    /** Matches a proto TestResult back to its originating entity TestCase by test ID. */
    private int indexOfTestId(List<com.sentinel.rag.entity.TestCase> testCases, String testId) {
        for (int i = 0; i < testCases.size(); i++) {
            if (testCases.get(i).getTestId().toString().equals(testId)) {
                return i;
            }
        }
        throw new IllegalStateException(
                "gRPC response referenced unknown test_id: " + testId);
    }
}