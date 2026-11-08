package com.sentinel.rag.orchestration;

import com.sentinel.rag.cache.SemanticCacheService;
import com.sentinel.rag.entity.*;
import com.sentinel.rag.gate.QualityGateResult;
import com.sentinel.rag.gate.QualityGateService;
import com.sentinel.rag.github.GitHubStatusClient;
import com.sentinel.rag.github.PrCommentService;
import com.sentinel.rag.grpc.*;
import com.sentinel.rag.pipeline.PipelineConfigData;
import com.sentinel.rag.pipeline.PipelineConfigFetcher;
import com.sentinel.rag.repository.CiBuildRunRepository;
import com.sentinel.rag.repository.TestCaseRepository;
import com.sentinel.rag.repository.TestResultRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationOrchestrationServiceTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private CiBuildRunRepository ciBuildRunRepository;

    @Mock
    private TestResultRecordRepository testResultRecordRepository;

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private RagEngineGrpcClient grpcClient;

    @Mock
    private RagEvaluationMapper mapper;

    @Mock
    private QualityGateService qualityGateService;

    @Mock
    private PipelineConfigFetcher pipelineConfigFetcher;

    @Mock
    private GitHubStatusClient gitHubStatusClient;

    @Mock
    private PrCommentService prCommentService;

    private EvaluationOrchestrationService service;
    private final String defaultRepo = "GodXSpell/SentinentRAG";

    @BeforeEach
    void setUp() {
        service = new EvaluationOrchestrationService(
                testCaseRepository,
                ciBuildRunRepository,
                testResultRecordRepository,
                semanticCacheService,
                grpcClient,
                mapper,
                qualityGateService,
                pipelineConfigFetcher,
                gitHubStatusClient,
                prCommentService,
                defaultRepo
        );
    }

    @Test
    void sweepStaleRunningBuildsOnStartup_marksOrphanedBuildsAsError() {
        CiBuildRun staleRun = new CiBuildRun();
        staleRun.setStatus(BuildStatus.RUNNING);
        staleRun.setCommitSha("abc1234567890");

        when(ciBuildRunRepository.findByStatus(BuildStatus.RUNNING)).thenReturn(List.of(staleRun));

        service.sweepStaleRunningBuildsOnStartup();

        assertEquals(BuildStatus.ERROR, staleRun.getStatus());
        verify(ciBuildRunRepository).save(staleRun);
        verify(gitHubStatusClient).updateStatus(eq(staleRun), contains("interrupted"));
    }

    @Test
    void runEvaluation_marksSkippedWhenSuiteIsEmpty() {
        UUID suiteId = UUID.randomUUID();
        CiBuildRun buildRun = new CiBuildRun();
        buildRun.setCommitSha("sha123");
        buildRun.setStatus(BuildStatus.RUNNING);

        when(testCaseRepository.findBySuite_SuiteId(suiteId)).thenReturn(Collections.emptyList());
        when(ciBuildRunRepository.save(any(CiBuildRun.class))).thenAnswer(i -> i.getArgument(0));

        CiBuildRun result = service.runEvaluation(buildRun, suiteId);

        assertEquals(BuildStatus.SKIPPED, result.getStatus());
        verify(gitHubStatusClient, atLeastOnce()).updateStatus(eq(buildRun), contains("Skipped"));
        verifyNoInteractions(grpcClient);
    }

    @Test
    void runEvaluation_allCacheHits_skipsGrpcAndPassesGate() {
        UUID suiteId = UUID.randomUUID();
        CiBuildRun buildRun = new CiBuildRun();
        buildRun.setCommitSha("sha123");
        buildRun.setStatus(BuildStatus.RUNNING);

        com.sentinel.rag.entity.TestCase tc1 = new com.sentinel.rag.entity.TestCase();
        tc1.setTestId(UUID.randomUUID());
        tc1.setQuery("q1");
        tc1.setExpectedGroundTruth("gt1");
        tc1.setExpectsRefusal(false);

        when(testCaseRepository.findBySuite_SuiteId(suiteId)).thenReturn(List.of(tc1));

        EngineInfoResponse engineInfo = EngineInfoResponse.newBuilder()
                .setEngineVersion("v1")
                .setEvaluatorModel("cross-encoder/nli-deberta-v3-small")
                .setRerankerModel("ms-marco")
                .build();
        when(grpcClient.getEngineInfo()).thenReturn(engineInfo);

        PipelineConfigData configData = PipelineConfigData.defaultConfiguration();
        when(pipelineConfigFetcher.fetchAndValidate(eq(defaultRepo), eq("sha123"))).thenReturn(configData);
        when(pipelineConfigFetcher.computeIndexHash(anyInt(), anyInt(), anyString(), anyString())).thenReturn("idx_hash");
        when(pipelineConfigFetcher.computeConfigHash(anyString(), anyString(), anyString(), any(), anyString())).thenReturn("cfg_hash");

        when(semanticCacheService.computeHash(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn("cache_hash_1");

        EvalCache cachedEntry = new EvalCache();
        cachedEntry.setGeneratedAnswer("Cached answer");
        cachedEntry.setFaithfulnessScore(0.95f);
        cachedEntry.setContextPrecisionScore(0.90f);
        cachedEntry.setAnswerRelevancyScore(0.92f);
        cachedEntry.setRefused(false);

        when(semanticCacheService.lookup("cache_hash_1")).thenReturn(Optional.of(cachedEntry));

        QualityGateResult passedGate = new QualityGateResult(true, List.of());
        when(qualityGateService.evaluate(any(), anyList())).thenReturn(passedGate);
        when(ciBuildRunRepository.save(any(CiBuildRun.class))).thenAnswer(i -> i.getArgument(0));

        CiBuildRun result = service.runEvaluation(buildRun, suiteId);

        assertEquals(BuildStatus.PASSED, result.getStatus());
        verify(grpcClient, never()).prepareIndex(any());
        verify(grpcClient, never()).runEvaluation(any());
        verify(testResultRecordRepository).saveAll(anyList());
        verify(prCommentService).postOrUpdateReport(any(), eq(passedGate), anyString(), eq(0L), eq(true));
    }
}

