package com.sentinel.rag.cache;

import com.sentinel.rag.entity.EvalCache;
import com.sentinel.rag.repository.EvalCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Service for caching evaluation results and looking up cached test results per FR-1.3.
 * Delegates cache key generation to {@link CacheKeyBuilder}.
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final EvalCacheRepository evalCacheRepository;

    public SemanticCacheService(EvalCacheRepository evalCacheRepository) {
        this.evalCacheRepository = Objects.requireNonNull(evalCacheRepository, "evalCacheRepository must not be null");
    }

    /**
     * Computes the deterministic cache key using the canonical 6-part hash.
     *
     * @param promptTemplate      Prompt template
     * @param configHash          Canonical pipeline configuration hash
     * @param query               Query text
     * @param expectedGroundTruth Golden ground truth text
     * @param expectsRefusal      Whether refusal is expected
     * @param corpusVersion       Corpus version fingerprint
     * @return 64-character lowercase hex SHA-256 cache key
     */
    public String computeHash(
            String promptTemplate,
            String configHash,
            String query,
            String expectedGroundTruth,
            boolean expectsRefusal,
            String corpusVersion
    ) {
        return CacheKeyBuilder.buildCacheHash(
                promptTemplate, configHash, query, expectedGroundTruth, expectsRefusal, corpusVersion
        );
    }

    /**
     * Overload for callers using ConfigParams, eliminating contextChunk (B-3 fix).
     */
    public String computeHash(
            String promptTemplate,
            ConfigParams configParams,
            String query,
            String expectedGroundTruth
    ) {
        String configHash = HexFormat.of().formatHex(
                CacheKeyBuilder.sha256(configParams.toCanonicalString())
        );
        return CacheKeyBuilder.buildCacheHash(
                promptTemplate,
                configHash,
                query,
                expectedGroundTruth,
                false,
                "v1"
        );
    }

    /** Sub-5ms path: a cache hit means the gRPC call to Python is skipped entirely. */
    public Optional<EvalCache> lookup(String cacheHash) {
        return evalCacheRepository.findById(cacheHash);
    }

    public EvalCache store(
            String cacheHash,
            String generatedAnswer,
            float faithfulnessScore,
            float contextPrecisionScore,
            float answerRelevancyScore,
            String evaluatorModel
    ) {
        return store(cacheHash, generatedAnswer, faithfulnessScore, contextPrecisionScore,
                answerRelevancyScore, evaluatorModel, false, 0, false);
    }

    /**
     * Idempotent cache write executed in an isolated transaction to prevent
     * concurrent webhook race conditions from poisoning the outer evaluation transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EvalCache store(
            String cacheHash,
            String generatedAnswer,
            float faithfulnessScore,
            float contextPrecisionScore,
            float answerRelevancyScore,
            String evaluatorModel,
            boolean refused,
            int retryCount,
            boolean selfCorrectionTriggered
    ) {
        try {
            Optional<EvalCache> existing = evalCacheRepository.findById(cacheHash);
            EvalCache entry = existing.orElseGet(EvalCache::new);
            entry.setCacheHash(cacheHash);
            entry.setGeneratedAnswer(generatedAnswer);
            entry.setFaithfulnessScore(faithfulnessScore);
            entry.setContextPrecisionScore(contextPrecisionScore);
            entry.setAnswerRelevancyScore(answerRelevancyScore);
            entry.setEvaluatorModel(evaluatorModel);
            entry.setRefused(refused);
            entry.setRetryCount(retryCount);
            entry.setSelfCorrectionTriggered(selfCorrectionTriggered);
            return evalCacheRepository.save(entry);
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent eval_cache write race condition ignored for hash {}", cacheHash);
            return evalCacheRepository.findById(cacheHash).orElse(null);
        }
    }
}
