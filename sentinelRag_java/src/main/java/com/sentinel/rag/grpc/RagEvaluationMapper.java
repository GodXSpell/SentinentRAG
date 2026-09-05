package com.sentinel.rag.grpc;

import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.entity.TestResultRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Translates between this codebase's JPA entities and the generated proto message types.
 *
 * IMPORTANT NAME COLLISION: com.sentinel.rag.entity.TestCase (JPA) and
 * com.sentinel.rag.grpc.TestCase (generated from rag_service.proto) are two entirely
 * different classes that happen to share a name — likewise entity.TestResultRecord vs.
 * grpc.TestResult. Every method here is explicit about which one it's converting to/from
 * specifically so this ambiguity never leaks into orchestration code, which should only
 * ever import the entity versions.
 */
@Component
public class RagEvaluationMapper {

    /**
     * Builds the gRPC request from a suite's test cases. cacheHash is populated by the
     * caller (SemanticCacheService already computed it before this mapper runs) so the
     * Python side can echo it back for cache-population purposes if it chooses to.
     */
    public EvalBatchRequest toEvalBatchRequest(
            UUID suiteId,
            List<com.sentinel.rag.entity.TestCase> testCases,
            List<String> promptTemplates,
            List<String> cacheHashes,
            boolean forceBypassCache
    ) {
        EvalBatchRequest.Builder requestBuilder = EvalBatchRequest.newBuilder()
                .setSuiteId(suiteId.toString())
                .setForceBypassCache(forceBypassCache);

        for (int i = 0; i < testCases.size(); i++) {
            com.sentinel.rag.entity.TestCase entityCase = testCases.get(i);
            TestCase protoCase = TestCase.newBuilder()
                    .setTestId(entityCase.getTestId().toString())
                    .setQuery(entityCase.getQuery())
                    .setExpectedGroundTruth(entityCase.getExpectedGroundTruth())
                    .setPromptTemplate(promptTemplates.get(i))
                    .setCacheHash(cacheHashes.get(i))
                    .build();
            requestBuilder.addTestCases(protoCase);
        }

        return requestBuilder.build();
    }

    /**
     * Converts one proto TestResult (from the gRPC response) into a persistable
     * TestResultRecord entity, linked to the given build run. Does NOT save it —
     * that's the caller's responsibility, keeping this mapper a pure function.
     */
    public TestResultRecord toTestResultRecord(TestResult protoResult, CiBuildRun buildRun) {
        TestResultRecord record = new TestResultRecord();
        record.setBuildRun(buildRun);
        record.setTestId(UUID.fromString(protoResult.getTestId()));
        record.setFaithfulnessScore(protoResult.getFaithfulnessScore());
        record.setContextPrecisionScore(protoResult.getContextPrecisionScore());
        record.setAnswerRelevancyScore(protoResult.getAnswerRelevancyScore());
        record.setExecutionTimeMs(protoResult.getExecutionTimeMs());
        record.setWasCached(protoResult.getWasCached());
        record.setGeneratedAnswer(protoResult.getGeneratedAnswer());
        return record;
    }

    /**
     * Applies an EvalBatchResponse's aggregate figures onto an existing CiBuildRun,
     * flipping it from RUNNING to PASSED/FAILED. The actual PASSED/FAILED decision comes
     * from QualityGateService, not from this mapper — this only sets the raw numbers;
     * the caller sets the enum after running the quality gate separately.
     */
    public void applyAggregatesToBuildRun(CiBuildRun buildRun, EvalBatchResponse response,
                                          float cacheHitRate) {
        buildRun.setMeanFaithfulness(response.getMeanFaithfulness());
        buildRun.setMeanPrecision(response.getMeanContextPrecision());
        buildRun.setCacheHitRate(cacheHitRate);
        buildRun.setExecutionDurationMs(response.getTotalDurationMs());
    }
}