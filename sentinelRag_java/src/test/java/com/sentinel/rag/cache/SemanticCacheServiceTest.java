package com.sentinel.rag.cache;

import com.sentinel.rag.entity.EvalCache;
import com.sentinel.rag.repository.EvalCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private EvalCacheRepository evalCacheRepository;

    private SemanticCacheService semanticCacheService;

    @BeforeEach
    void setUp() {
        semanticCacheService = new SemanticCacheService(evalCacheRepository);
    }

    @Test
    @DisplayName("computeHash with ConfigParams does not require contextChunk and returns 64-char hex key")
    void testComputeHashWithoutContextChunk() {
        ConfigParams configParams = new ConfigParams("eval-v1", "rerank-v1", 20);
        String hash = semanticCacheService.computeHash(
                "You are an assistant",
                configParams,
                "What is RAG?",
                "Retrieval Augmented Generation"
        );

        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("lookup delegates directly to evalCacheRepository.findById")
    void testLookup() {
        String testHash = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234";
        EvalCache mockEntry = new EvalCache();
        mockEntry.setCacheHash(testHash);
        when(evalCacheRepository.findById(testHash)).thenReturn(Optional.of(mockEntry));

        Optional<EvalCache> result = semanticCacheService.lookup(testHash);

        assertThat(result).isPresent();
        assertThat(result.get().getCacheHash()).isEqualTo(testHash);
        verify(evalCacheRepository).findById(testHash);
    }

    @Test
    @DisplayName("store persists EvalCache with provided scores and model")
    void testStore() {
        String testHash = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234";
        when(evalCacheRepository.save(any(EvalCache.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EvalCache saved = semanticCacheService.store(
                testHash,
                "Generated answer",
                0.95f,
                0.88f,
                0.91f,
                "cross-encoder/nli-deberta-v3-small"
        );

        assertThat(saved.getCacheHash()).isEqualTo(testHash);
        assertThat(saved.getGeneratedAnswer()).isEqualTo("Generated answer");
        assertThat(saved.getFaithfulnessScore()).isEqualTo(0.95f);
        assertThat(saved.getContextPrecisionScore()).isEqualTo(0.88f);
        assertThat(saved.getAnswerRelevancyScore()).isEqualTo(0.91f);
        assertThat(saved.getEvaluatorModel()).isEqualTo("cross-encoder/nli-deberta-v3-small");
        verify(evalCacheRepository).save(any(EvalCache.class));
    }
}

