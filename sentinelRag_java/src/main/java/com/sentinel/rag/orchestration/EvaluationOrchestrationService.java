package com.sentinel.rag.orchestration;

import com.sentinel.rag.cache.SemanticCacheService;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.entity.EvalCache;
import com.sentinel.rag.entity.TestCase;
import com.sentinel.rag.entity.TestResultRecord;
import com.sentinel.rag.gate.LatencyPercentileCalculator;
import com.sentinel.rag.gate.QualityGateResult;
import com.sentinel.rag.gate.QualityGateService;
import com.sentinel.rag.github.GitHubStatusClient;
import com.sentinel.rag.github.PrCommentService;
import com.sentinel.rag.grpc.EngineInfoResponse;
import com.sentinel.rag.grpc.EvalBatchRequest;
import com.sentinel.rag.grpc.EvalBatchResponse;
import com.sentinel.rag.grpc.PipelineConfig;
import com.sentinel.rag.grpc.PrepareIndexRequest;
import com.sentinel.rag.grpc.RagEngineGrpcClient;
import com.sentinel.rag.grpc.RagEvaluationMapper;
import com.sentinel.rag.grpc.TestResult;
import com.sentinel.rag.pipeline.PipelineConfigData;
import com.sentinel.rag.pipeline.PipelineConfigFetcher;
import com.sentinel.rag.repository.CiBuildRunRepository;
import com.sentinel.rag.repository.TestCaseRepository;
import com.sentinel.rag.repository.TestResultRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Orchestrates the full evaluation lifecycle per Plan §3.4 and §3.5:
 * 1. Startup crash sweep marking orphaned RUNNING builds as ERROR.
 * 2. Fetches engine version & PR pipeline configuration.
 * 3. Applies stable canary sampling for PRs (seed: suiteId#prNumber).
 * 4. Sub-5ms semantic cache lookup with refusal tracking.
 * 5. Calls PrepareIndex and RunEvaluation on cache misses.
 * 6. Evaluates QualityGate with refusal accuracy and non-cached p95 latency.
 * 7. Publishes GitHub commit status checks and markdown PR comment reports.
 */
@Service
public class EvaluationOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationOrchestrationService.class);

    private final TestCaseRepository testCaseRepository;
    private final CiBuildRunRepository ciBuildRunRepository;
    private final TestResultRecordRepository testResultRecordRepository;
    private final SemanticCacheService semanticCacheService;
    private final RagEngineGrpcClient grpcClient;
    private final RagEvaluationMapper mapper;
    private final QualityGateService qualityGateService;
    private final PipelineConfigFetcher pipelineConfigFetcher;
    private final GitHubStatusClient gitHubStatusClient;
    private final PrCommentService prCommentService;
    private final String defaultRepo;

    public EvaluationOrchestrationService(
            TestCaseRepository testCaseRepository,
            CiBuildRunRepository ciBuildRunRepository,
            TestResultRecordRepository testResultRecordRepository,
            SemanticCacheService semanticCacheService,
            RagEngineGrpcClient grpcClient,
            RagEvaluationMapper mapper,
            QualityGateService qualityGateService,
            PipelineConfigFetcher pipelineConfigFetcher,
            GitHubStatusClient gitHubStatusClient,
            PrCommentService prCommentService,
            @Value("${sentinel.github.default-repo:GodXSpell/SentinentRAG}") String defaultRepo
    ) {
        this.testCaseRepository = Objects.requireNonNull(testCaseRepository);
        this.ciBuildRunRepository = Objects.requireNonNull(ciBuildRunRepository);
        this.testResultRecordRepository = Objects.requireNonNull(testResultRecordRepository);
        this.semanticCacheService = Objects.requireNonNull(semanticCacheService);
        this.grpcClient = Objects.requireNonNull(grpcClient);
        this.mapper = Objects.requireNonNull(mapper);
        this.qualityGateService = Objects.requireNonNull(qualityGateService);
        this.pipelineConfigFetcher = Objects.requireNonNull(pipelineConfigFetcher);
        this.gitHubStatusClient = Objects.requireNonNull(gitHubStatusClient);
        this.prCommentService = Objects.requireNonNull(prCommentService);
        this.defaultRepo = defaultRepo;
    }

    /**
     * Startup crash recovery sweep per Plan §3.5: marks stale RUNNING builds as ERROR.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void sweepStaleRunningBuildsOnStartup() {
        try {
            List<CiBuildRun> staleRuns = ciBuildRunRepository.findByStatus(BuildStatus.RUNNING);
            if (staleRuns.isEmpty()) {
                return;
            }

            log.warn("Found {} orphaned RUNNING build runs on startup; marking as ERROR", staleRuns.size());
            for (CiBuildRun run : staleRuns) {
                run.setStatus(BuildStatus.ERROR);
                ciBuildRunRepository.save(run);
                gitHubStatusClient.updateStatus(run, "Build interrupted by server restart");
            }
        } catch (Exception e) {
            log.warn("Crash sweep skipped (database unmigrated or unavailable): {}", e.getMessage());
        }
    }

    /**
     * Executes the evaluation pipeline for a build run and suite.
     */
    @Transactional
    public CiBuildRun runEvaluation(CiBuildRun buildRun, UUID suiteId) {
        gitHubStatusClient.updateStatus(buildRun, "Evaluating RAG pipeline quality gates...");

        List<TestCase> allSuiteCases = testCaseRepository.findBySuite_SuiteId(suiteId);

        if (allSuiteCases.isEmpty()) {
            buildRun.setMeanFaithfulness(0f);
            buildRun.setMeanPrecision(0f);
            buildRun.setRefusalAccuracy(null);
            buildRun.setCacheHitRate(0f);
            buildRun.setExecutionDurationMs(0L);
            buildRun.setStatus(BuildStatus.SKIPPED);
            CiBuildRun saved = ciBuildRunRepository.save(buildRun);
            gitHubStatusClient.updateStatus(saved, "Skipped — test suite has no cases");
            return saved;
        }

        // 1. Canary Sampling: stable seed = suiteId#prNumber, size = min(n, max(10, ceil(0.25n)))
        List<TestCase> evaluatedCases;
        String canaryInfo = "";
        if (buildRun.getPullRequestId() != null) {
            int n = allSuiteCases.size();
            int sampleSize = Math.min(n, Math.max(10, (int) Math.ceil(0.25 * n)));
            String seedStr = (suiteId != null ? suiteId.toString() : "suite") + "#" + buildRun.getPullRequestId();
            evaluatedCases = selectCanarySample(allSuiteCases, sampleSize, seedStr);
            String suitePrefix = suiteId != null ? suiteId.toString().substring(0, Math.min(8, suiteId.toString().length())) : "suite";
            canaryInfo = String.format("Canary evaluation: %d of %d cases evaluated (seed: %s#%d)",
                    evaluatedCases.size(), n, suitePrefix, buildRun.getPullRequestId());
        } else {
            evaluatedCases = allSuiteCases;
            canaryInfo = String.format("Full evaluation: %d cases evaluated", evaluatedCases.size());
        }

        // 2. Fetch Engine Info from Python plane
        String engineVersion = "v1-default";
        String evaluatorVersion = "cross-encoder/nli-deberta-v3-small";
        String rerankerModel = "cross-encoder/ms-marco-MiniLM-L-6-v2";
        try {
            EngineInfoResponse engineInfo = grpcClient.getEngineInfo();
            engineVersion = engineInfo.getEngineVersion();
            evaluatorVersion = engineInfo.getEvaluatorModel();
            rerankerModel = engineInfo.getRerankerModel();
        } catch (Exception e) {
            log.warn("Could not query GetEngineInfo from gRPC engine; using default parameters: {}", e.getMessage());
        }

        // 3. Fetch and Validate PR Pipeline Configuration
        PipelineConfigData configData = pipelineConfigFetcher.fetchAndValidate(
                defaultRepo, buildRun.getCommitSha()
        );
        String corpusVersion = "v1";
        String indexHash = pipelineConfigFetcher.computeIndexHash(
                configData.chunkSizeTokens(),
                configData.chunkOverlapTokens(),
                configData.embeddingModel(),
                corpusVersion
        );
        String configHash = pipelineConfigFetcher.computeConfigHash(
                engineVersion,
                evaluatorVersion,
                rerankerModel,
                configData,
                corpusVersion
        );

        // 4. Per-Case Semantic Cache Lookup
        List<TestCase> cacheMisses = new ArrayList<>();
        List<String> missCacheHashes = new ArrayList<>();
        List<TestResultRecord> results = new ArrayList<>();
        List<RagEvaluationMapper.CaseOutcome> outcomes = new ArrayList<>();
        int cacheHits = 0;

        for (TestCase testCase : evaluatedCases) {
            String cacheHash = semanticCacheService.computeHash(
                    configData.promptTemplate(),
                    configHash,
                    testCase.getQuery(),
                    testCase.getExpectedGroundTruth(),
                    testCase.isExpectsRefusal(),
                    corpusVersion
            );

            Optional<EvalCache> cached = semanticCacheService.lookup(cacheHash);
            if (cached.isPresent()) {
                cacheHits++;
                EvalCache hit = cached.get();
                TestResultRecord record = fromCacheHit(hit, testCase, buildRun);
                results.add(record);
                outcomes.add(new RagEvaluationMapper.CaseOutcome(
                        testCase.isExpectsRefusal(),
                        hit.isRefused(),
                        hit.getFaithfulnessScore(),
                        hit.getContextPrecisionScore(),
                        0L
                ));
            } else {
                cacheMisses.add(testCase);
                missCacheHashes.add(cacheHash);
            }
        }

        // 5. Evaluate Cache Misses via gRPC
        long totalDurationMs = 0;
        if (!cacheMisses.isEmpty()) {
            PipelineConfig protoConfig = mapper.toProtoPipelineConfig(
                    configData, indexHash, configHash, defaultRepo, buildRun.getCommitSha(), corpusVersion
            );

            // Prepare Index RPC
            PrepareIndexRequest prepRequest = PrepareIndexRequest.newBuilder()
                    .setConfig(protoConfig)
                    .build();
            try {
                grpcClient.prepareIndex(prepRequest);
            } catch (Exception e) {
                log.error("PrepareIndex failed for index_hash {}: {}", indexHash, e.getMessage());
                throw e;
            }

            // Run Evaluation RPC
            List<String> promptTemplates = cacheMisses.stream()
                    .map(tc -> configData.promptTemplate())
                    .toList();

            EvalBatchRequest evalRequest = mapper.toEvalBatchRequest(
                    suiteId, cacheMisses, promptTemplates, missCacheHashes, protoConfig, false
            );

            EvalBatchResponse response = grpcClient.runEvaluation(evalRequest);
            totalDurationMs = response.getTotalDurationMs();

            for (TestResult protoResult : response.getResultsList()) {
                TestResultRecord record = mapper.toTestResultRecord(protoResult, buildRun);
                results.add(record);

                int idx = indexOfTestId(cacheMisses, protoResult.getTestId());
                TestCase matchedCase = cacheMisses.get(idx);
                String matchingHash = missCacheHashes.get(idx);

                outcomes.add(new RagEvaluationMapper.CaseOutcome(
                        matchedCase.isExpectsRefusal(),
                        protoResult.getRefused(),
                        protoResult.getFaithfulnessScore(),
                        protoResult.getContextPrecisionScore(),
                        protoResult.getExecutionTimeMs()
                ));

                // Populate Cache with Refusal fields (Plan §3.1)
                try {
                    semanticCacheService.store(
                            matchingHash,
                            protoResult.getGeneratedAnswer(),
                            protoResult.getFaithfulnessScore(),
                            protoResult.getContextPrecisionScore(),
                            protoResult.getAnswerRelevancyScore(),
                            evaluatorVersion,
                            protoResult.getRefused(),
                            protoResult.getRetryCount(),
                            protoResult.getSelfCorrectionTriggered()
                    );
                } catch (Exception e) {
                    log.debug("Concurrent eval_cache write race condition ignored for hash {}: {}", matchingHash, e.getMessage());
                }
            }
        }

        testResultRecordRepository.saveAll(results);

        // 6. Aggregate Metrics with Refusal Handling (Plan §1.3)
        float cacheHitRate = evaluatedCases.isEmpty() ? 0f : (float) cacheHits / evaluatedCases.size();
        mapper.applyAggregatesWithRefusals(buildRun, outcomes, cacheHitRate, totalDurationMs);

        // 7. Evaluate Quality Gate
        QualityGateResult gateResult = qualityGateService.evaluate(buildRun, results);
        buildRun.setStatus(gateResult.passed() ? BuildStatus.PASSED : BuildStatus.FAILED);
        CiBuildRun saved = ciBuildRunRepository.save(buildRun);

        // 8. Publish Commit Status & PR Comment
        gitHubStatusClient.updateStatus(saved, null);

        List<Long> nonCachedTimes = results.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getWasCached()))
                .map(TestResultRecord::getExecutionTimeMs)
                .toList();
        long p95Latency = nonCachedTimes.isEmpty() ? 0L : LatencyPercentileCalculator.computeP95(nonCachedTimes);
        boolean allCached = nonCachedTimes.isEmpty();

        prCommentService.postOrUpdateReport(saved, gateResult, canaryInfo, p95Latency, allCached);

        return saved;
    }

    private List<TestCase> selectCanarySample(
            List<TestCase> testCases,
            int sampleSize,
            String seed
    ) {
        if (testCases.size() <= sampleSize) {
            return new ArrayList<>(testCases);
        }

        List<TestCase> shuffled = new ArrayList<>(testCases);
        shuffled.sort(Comparator.comparing(TestCase::getQuery));
        Random random = new Random((long) seed.hashCode());
        Collections.shuffle(shuffled, random);

        return shuffled.subList(0, sampleSize);
    }

    private TestResultRecord fromCacheHit(
            EvalCache cached,
            TestCase testCase,
            CiBuildRun buildRun
    ) {
        TestResultRecord record = new TestResultRecord();
        record.setBuildRun(buildRun);
        UUID testId = testCase.getTestId() != null ? testCase.getTestId() : UUID.randomUUID();
        record.setTestId(testId);
        record.setFaithfulnessScore(cached.getFaithfulnessScore());
        record.setContextPrecisionScore(cached.getContextPrecisionScore());
        record.setAnswerRelevancyScore(cached.getAnswerRelevancyScore());
        record.setExecutionTimeMs(0L);
        record.setWasCached(true);
        record.setGeneratedAnswer(cached.getGeneratedAnswer());
        return record;
    }

    private int indexOfTestId(List<TestCase> testCases, String testId) {
        if (testId != null) {
            for (int i = 0; i < testCases.size(); i++) {
                TestCase tc = testCases.get(i);
                if (tc.getTestId() != null && tc.getTestId().toString().equals(testId)) {
                    return i;
                }
            }
        }
        // Fallback: match by index if testId was synthetic or integer string
        try {
            int parsed = Integer.parseInt(testId);
            if (parsed >= 0 && parsed < testCases.size()) {
                return parsed;
            }
        } catch (Exception ignored) {
        }

        if (!testCases.isEmpty()) {
            return 0;
        }
        throw new IllegalStateException("gRPC response referenced unknown test_id: " + testId);
    }
}
