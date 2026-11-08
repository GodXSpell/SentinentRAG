package com.sentinel.rag.grpc;

import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.entity.TestResultRecord;
import com.sentinel.rag.pipeline.PipelineConfigData;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Translates between JPA entities, domain pipeline configuration,
 * and generated proto message types per Plan §1.2, §1.3, and §3.4.
 *
 * NOTE: Disambiguates between JPA TestCase and protobuf TestCase cleanly.
 */
@Component
public class RagEvaluationMapper {

    public record CaseOutcome(
            boolean expectsRefusal,
            boolean refused,
            float faithfulness,
            float precision,
            long executionTimeMs
    ) {}

    @SuppressWarnings("deprecation")
    public EvalBatchRequest toEvalBatchRequest(
            UUID suiteId,
            List<com.sentinel.rag.entity.TestCase> testCases,
            List<String> promptTemplates,
            List<String> cacheHashes,
            PipelineConfig config,
            boolean forceBypassCache
    ) {
        EvalBatchRequest.Builder requestBuilder = EvalBatchRequest.newBuilder()
                .setSuiteId(suiteId != null ? suiteId.toString() : "")
                .setForceBypassCache(forceBypassCache);

        if (config != null) {
            requestBuilder.setConfig(config);
        }

        for (int i = 0; i < testCases.size(); i++) {
            com.sentinel.rag.entity.TestCase entityCase = testCases.get(i);
            String testIdStr = entityCase.getTestId() != null
                    ? entityCase.getTestId().toString()
                    : UUID.randomUUID().toString();

            TestCase.Builder protoCase = TestCase.newBuilder()
                    .setTestId(testIdStr)
                    .setQuery(entityCase.getQuery() != null ? entityCase.getQuery() : "")
                    .setExpectedGroundTruth(entityCase.getExpectedGroundTruth() != null ? entityCase.getExpectedGroundTruth() : "")
                    .setExpectsRefusal(entityCase.isExpectsRefusal())
                    .setCacheHash(cacheHashes != null && i < cacheHashes.size() && cacheHashes.get(i) != null ? cacheHashes.get(i) : "");

            if (promptTemplates != null && i < promptTemplates.size() && promptTemplates.get(i) != null) {
                protoCase.setPromptTemplate(promptTemplates.get(i));
            }

            requestBuilder.addTestCases(protoCase.build());
        }

        return requestBuilder.build();
    }

    public EvalBatchRequest toEvalBatchRequest(
            UUID suiteId,
            List<com.sentinel.rag.entity.TestCase> testCases,
            List<String> promptTemplates,
            List<String> cacheHashes,
            boolean forceBypassCache
    ) {
        return toEvalBatchRequest(suiteId, testCases, promptTemplates, cacheHashes, null, forceBypassCache);
    }

    public PipelineConfig toProtoPipelineConfig(
            PipelineConfigData data,
            String indexHash,
            String configHash,
            String repo,
            String commitSha,
            String corpusVersion
    ) {
        CorpusRef.Builder corpusRef = CorpusRef.newBuilder()
                .setRepoFullName(repo != null ? repo : "")
                .setRefSha(commitSha != null ? commitSha : "")
                .setCorpusPath(data.corpusPath() != null ? data.corpusPath() : "corpus/");

        return PipelineConfig.newBuilder()
                .setConfigHash(configHash != null ? configHash : "")
                .setIndexHash(indexHash != null ? indexHash : "")
                .setPromptTemplate(data.promptTemplate() != null ? data.promptTemplate() : "")
                .setChunkSizeTokens(data.chunkSizeTokens())
                .setChunkOverlapTokens(data.chunkOverlapTokens())
                .setRetrievalTopK(data.retrievalTopK())
                .setRerankTopK(data.rerankTopK())
                .setEmbeddingModel(data.embeddingModel() != null ? data.embeddingModel() : "")
                .setGeneratorProvider(data.generatorProvider() != null ? data.generatorProvider() : "")
                .setGeneratorModel(data.generatorModel() != null ? data.generatorModel() : "")
                .setCorpusVersion(corpusVersion != null ? corpusVersion : "v1")
                .setCorpusRef(corpusRef)
                .build();
    }

    public TestResultRecord toTestResultRecord(TestResult protoResult, CiBuildRun buildRun) {
        TestResultRecord record = new TestResultRecord();
        record.setBuildRun(buildRun);

        UUID testUuid;
        try {
            testUuid = UUID.fromString(protoResult.getTestId());
        } catch (Exception e) {
            testUuid = UUID.nameUUIDFromBytes(
                    protoResult.getTestId() != null
                            ? protoResult.getTestId().getBytes(StandardCharsets.UTF_8)
                            : new byte[0]
            );
        }

        record.setTestId(testUuid);
        record.setFaithfulnessScore(protoResult.getFaithfulnessScore());
        record.setContextPrecisionScore(protoResult.getContextPrecisionScore());
        record.setAnswerRelevancyScore(protoResult.getAnswerRelevancyScore());
        record.setExecutionTimeMs(protoResult.getExecutionTimeMs());
        record.setWasCached(protoResult.getWasCached());
        record.setGeneratedAnswer(protoResult.getGeneratedAnswer() != null ? protoResult.getGeneratedAnswer() : "");
        return record;
    }

    /**
     * Applies evaluation aggregate figures onto CiBuildRun enforcing Plan §1.3 refusal rules:
     * 1. Correct refusals are excluded from score means.
     * 2. Missed or unexpected refusals enter score means with 0.0.
     * 3. Refusal accuracy is computed when >= 1 declared refusal case exists, else set to null.
     */
    public void applyAggregatesWithRefusals(
            CiBuildRun buildRun,
            List<CaseOutcome> outcomes,
            float cacheHitRate,
            long totalDurationMs
    ) {
        if (outcomes.isEmpty()) {
            buildRun.setMeanFaithfulness(0f);
            buildRun.setMeanPrecision(0f);
            buildRun.setRefusalAccuracy(null);
            buildRun.setCacheHitRate(cacheHitRate);
            buildRun.setExecutionDurationMs(0L);
            return;
        }

        int declaredRefusalCount = 0;
        int correctRefusalCount = 0;

        double sumFaithfulness = 0.0;
        double sumPrecision = 0.0;
        int scoreCount = 0;

        for (CaseOutcome outcome : outcomes) {
            if (outcome.expectsRefusal()) {
                declaredRefusalCount++;
                if (outcome.refused()) {
                    correctRefusalCount++;
                    // Plan §1.3: Exclude correct refusal from score means
                    continue;
                }
            }

            // Normal non-refusal case, or missed refusal, or unexpected refusal
            sumFaithfulness += outcome.faithfulness();
            sumPrecision += outcome.precision();
            scoreCount++;
        }

        Float refusalAccuracy = declaredRefusalCount > 0
                ? (float) correctRefusalCount / declaredRefusalCount
                : null;

        float meanFaithfulness = scoreCount > 0 ? (float) (sumFaithfulness / scoreCount) : 1.0f;
        float meanPrecision = scoreCount > 0 ? (float) (sumPrecision / scoreCount) : 1.0f;

        buildRun.setMeanFaithfulness(meanFaithfulness);
        buildRun.setMeanPrecision(meanPrecision);
        buildRun.setRefusalAccuracy(refusalAccuracy);
        buildRun.setCacheHitRate(cacheHitRate);
        buildRun.setExecutionDurationMs(totalDurationMs);
    }

    public void applyAggregatesToBuildRun(CiBuildRun buildRun, EvalBatchResponse response, float cacheHitRate) {
        buildRun.setMeanFaithfulness(response.getMeanFaithfulness());
        buildRun.setMeanPrecision(response.getMeanContextPrecision());
        buildRun.setCacheHitRate(cacheHitRate);
        buildRun.setExecutionDurationMs(response.getTotalDurationMs());
    }
}
