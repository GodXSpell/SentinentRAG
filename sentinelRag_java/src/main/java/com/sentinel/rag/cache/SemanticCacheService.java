package com.sentinel.rag.cache;

import com.sentinel.rag.entity.EvalCache;
import com.sentinel.rag.repository.EvalCacheRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Implements FR-1.3: SHA256(PromptTemplate + ConfigParams + Query + ContextChunk) as the
 * cache key, giving sub-5ms cache hits that skip the gRPC call to Python entirely.
 *
 * IMPORTANT — naive string concatenation before hashing is a real collision risk:
 * "ab" + "c" produces the identical string to "a" + "bc", so two logically different
 * inputs could collide onto the same hash if you just do promptTemplate + query + ...
 * directly. This class avoids that by hashing each component separately first, then
 * hashing the concatenation of those fixed-length (32-byte) digests together — fixed-length
 * inputs to the final hash can never be ambiguously split, so this closes the collision
 * path structurally rather than by hoping a delimiter never appears in the input text.
 */
@Service
public class SemanticCacheService {

    private static final String ALGORITHM = "SHA-256";

    private final EvalCacheRepository evalCacheRepository;

    public SemanticCacheService(EvalCacheRepository evalCacheRepository) {
        this.evalCacheRepository = evalCacheRepository;
    }

    /**
     * Computes the cache key for a given evaluation input. Deterministic: identical
     * inputs always produce the identical hash, which is the entire point — it's how
     * a cache hit is recognized on the next CI run over unchanged code.
     */
    public String computeHash(String promptTemplate, ConfigParams configParams,
                              String query, String contextChunk) {
        byte[] promptDigest = sha256(promptTemplate);
        byte[] configDigest = sha256(configParams.toCanonicalString());
        byte[] queryDigest = sha256(query);
        byte[] contextDigest = sha256(contextChunk);

        byte[] combined = new byte[promptDigest.length + configDigest.length
                + queryDigest.length + contextDigest.length];
        int offset = 0;
        offset = writeAt(combined, offset, promptDigest);
        offset = writeAt(combined, offset, configDigest);
        offset = writeAt(combined, offset, queryDigest);
        writeAt(combined, offset, contextDigest);

        return HexFormat.of().formatHex(sha256Bytes(combined));
    }

    /** Sub-5ms path: a cache hit means the gRPC call to Python is skipped entirely. */
    public Optional<EvalCache> lookup(String cacheHash) {
        return evalCacheRepository.findById(cacheHash);
    }

    public EvalCache store(String cacheHash, String generatedAnswer, float faithfulnessScore,
                           float contextPrecisionScore, float answerRelevancyScore,
                           String evaluatorModel) {
        EvalCache entry = new EvalCache();
        entry.setCacheHash(cacheHash);
        entry.setGeneratedAnswer(generatedAnswer);
        entry.setFaithfulnessScore(faithfulnessScore);
        entry.setContextPrecisionScore(contextPrecisionScore);
        entry.setAnswerRelevancyScore(answerRelevancyScore);
        entry.setEvaluatorModel(evaluatorModel);
        return evalCacheRepository.save(entry);
    }

    private byte[] sha256(String input) {
        return sha256Bytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256Bytes(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JVM (part of the JDK's
            // mandatory algorithm set) — this can only happen on a broken/non-standard
            // JVM install, which is a fatal environment problem, not a recoverable one.
            throw new IllegalStateException("SHA-256 not available in this JVM", e);
        }
    }

    private int writeAt(byte[] target, int offset, byte[] source) {
        System.arraycopy(source, 0, target, offset, source.length);
        return offset + source.length;
    }
}