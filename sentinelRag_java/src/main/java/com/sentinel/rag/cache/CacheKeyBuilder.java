package com.sentinel.rag.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Builds deterministic, collision-resistant evaluation cache keys per FR-1.3 and Implementation Plan §1.2.
 *
 * <p>Why fixed-length inner digests:
 * Naive string concatenation (e.g. {@code prompt + config + query}) is vulnerable to boundary shifting:
 * "ab" + "c" produces the identical sequence to "a" + "bc". Delimiters like '|' or ':' fail if the delimiter
 * appears inside arbitrary prompt templates or golden answers. By hashing each field independently into a
 * fixed 32-byte SHA-256 digest first and then hashing the concatenated 192 bytes, field boundaries are
 * mathematically immutable.
 *
 * <p>Why contextChunk was eliminated (B-3):
 * Java cannot know retrieved chunks before calling Python. The evaluation cache key must depend strictly on
 * inputs known before execution: prompt template, canonical config, query, expected ground truth,
 * refusal expectation, and corpus version.
 */
public final class CacheKeyBuilder {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int DIGEST_LENGTH_BYTES = 32;
    private static final int FIELD_COUNT = 6;
    private static final int TOTAL_PAYLOAD_BYTES = FIELD_COUNT * DIGEST_LENGTH_BYTES;

    private CacheKeyBuilder() {
        // Utility class
    }

    /**
     * Computes the final 64-character lowercase hex cache key for a single test case.
     *
     * @param promptTemplate       The prompt template string
     * @param configHash           SHA-256 digest of the canonical pipeline configuration
     * @param query                The test query
     * @param expectedGroundTruth  Expected answer (golden reference)
     * @param expectsRefusal       Whether the case expects the model to refuse
     * @param corpusVersion        Content fingerprint of the corpus files
     * @return 64-character lowercase hexadecimal SHA-256 digest
     */
    public static String buildCacheHash(
            String promptTemplate,
            String configHash,
            String query,
            String expectedGroundTruth,
            boolean expectsRefusal,
            String corpusVersion
    ) {
        Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
        Objects.requireNonNull(configHash, "configHash must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(expectedGroundTruth, "expectedGroundTruth must not be null");
        Objects.requireNonNull(corpusVersion, "corpusVersion must not be null");

        byte[] promptDigest = sha256(promptTemplate);
        byte[] configDigest = sha256(configHash);
        byte[] queryDigest = sha256(query);
        byte[] groundTruthDigest = sha256(expectedGroundTruth);
        byte[] refusalDigest = sha256(String.valueOf(expectsRefusal));
        byte[] corpusDigest = sha256(corpusVersion);

        byte[] combined = new byte[TOTAL_PAYLOAD_BYTES];
        int offset = 0;
        offset = writeDigest(combined, offset, promptDigest);
        offset = writeDigest(combined, offset, configDigest);
        offset = writeDigest(combined, offset, queryDigest);
        offset = writeDigest(combined, offset, groundTruthDigest);
        offset = writeDigest(combined, offset, refusalDigest);
        writeDigest(combined, offset, corpusDigest);

        return HexFormat.of().formatHex(sha256Bytes(combined));
    }

    public static byte[] sha256(String input) {
        return sha256Bytes(input.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256Bytes(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Mandatory JVM algorithm " + HASH_ALGORITHM + " missing", e);
        }
    }

    private static int writeDigest(byte[] target, int offset, byte[] digest) {
        System.arraycopy(digest, 0, target, offset, DIGEST_LENGTH_BYTES);
        return offset + DIGEST_LENGTH_BYTES;
    }
}

