package com.sentinel.rag.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheKeyBuilderTest {

    private static final String DEFAULT_PROMPT = "You are a helpful assistant: {query}";
    private static final String DEFAULT_CONFIG_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String DEFAULT_QUERY = "What is machine learning?";
    private static final String DEFAULT_GROUND_TRUTH = "Machine learning is a subfield of artificial intelligence.";
    private static final boolean DEFAULT_REFUSAL = false;
    private static final String DEFAULT_CORPUS_VERSION = "d8578edf8458ce06fbc5bb76a58c5ca4";

    @Test
    @DisplayName("Deterministic: identical inputs yield identical hash across 100 runs")
    void testDeterminism() {
        String baseHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        for (int i = 0; i < 100; i++) {
            String hash = CacheKeyBuilder.buildCacheHash(
                    DEFAULT_PROMPT,
                    DEFAULT_CONFIG_HASH,
                    DEFAULT_QUERY,
                    DEFAULT_GROUND_TRUTH,
                    DEFAULT_REFUSAL,
                    DEFAULT_CORPUS_VERSION
            );
            assertThat(hash).isEqualTo(baseHash);
        }
    }

    @Test
    @DisplayName("Prompt change alters cache hash")
    void testPromptChangeAltersHash() {
        String baseHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        String newHash = CacheKeyBuilder.buildCacheHash(
                "You are an adversarial assistant: {query}",
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        assertThat(newHash).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("Config hash change alters cache hash")
    void testConfigHashChangeAltersHash() {
        String baseHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        String newHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        assertThat(newHash).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("Query change alters cache hash")
    void testQueryChangeAltersHash() {
        String baseHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        String newHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                "What is deep learning?",
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        assertThat(newHash).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("Expected ground truth change alone busts cache hash")
    void testGroundTruthChangeBustsCache() {
        String baseHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        String newHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                "An edited golden reference answer that improves precision.",
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        assertThat(newHash).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("Refusal flag change busts cache hash")
    void testRefusalFlagChangeBustsCache() {
        String baseHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                false,
                DEFAULT_CORPUS_VERSION
        );

        String newHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                true,
                DEFAULT_CORPUS_VERSION
        );

        assertThat(newHash).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("Corpus version change busts cache hash")
    void testCorpusVersionChangeBustsCache() {
        String baseHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        String newHash = CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUERY,
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                "a1b2c3d4e5f6071829"
        );

        assertThat(newHash).isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("Boundary shifting between fields cannot collide due to fixed-length digests")
    void testBoundaryShiftingImmunity() {
        // Even if prompt and query could shift characters across a naive boundary:
        // prompt = "A", query = "BC" vs prompt = "AB", query = "C"
        String hash1 = CacheKeyBuilder.buildCacheHash(
                "A",
                DEFAULT_CONFIG_HASH,
                "BC",
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        String hash2 = CacheKeyBuilder.buildCacheHash(
                "AB",
                DEFAULT_CONFIG_HASH,
                "C",
                DEFAULT_GROUND_TRUTH,
                DEFAULT_REFUSAL,
                DEFAULT_CORPUS_VERSION
        );

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Null fields throw NullPointerException")
    void testNullChecks() {
        assertThatThrownBy(() -> CacheKeyBuilder.buildCacheHash(
                null, DEFAULT_CONFIG_HASH, DEFAULT_QUERY, DEFAULT_GROUND_TRUTH, false, DEFAULT_CORPUS_VERSION
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT, null, DEFAULT_QUERY, DEFAULT_GROUND_TRUTH, false, DEFAULT_CORPUS_VERSION
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT, DEFAULT_CONFIG_HASH, null, DEFAULT_GROUND_TRUTH, false, DEFAULT_CORPUS_VERSION
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT, DEFAULT_CONFIG_HASH, DEFAULT_QUERY, null, false, DEFAULT_CORPUS_VERSION
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> CacheKeyBuilder.buildCacheHash(
                DEFAULT_PROMPT, DEFAULT_CONFIG_HASH, DEFAULT_QUERY, DEFAULT_GROUND_TRUTH, false, null
        )).isInstanceOf(NullPointerException.class);
    }
}

