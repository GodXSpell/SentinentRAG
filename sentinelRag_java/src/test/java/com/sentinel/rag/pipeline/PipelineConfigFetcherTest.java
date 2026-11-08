package com.sentinel.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineConfigFetcherTest {

    private PipelineConfigFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new PipelineConfigFetcher(new ObjectMapper(), "");
    }

    @Test
    void parseAndValidate_succeedsWithAllowedValues() {
        String yaml = """
                prompt_template: "Custom prompt {query} {context}"
                chunk_size_tokens: 256
                chunk_overlap_tokens: 30
                retrieval_top_k: 15
                rerank_top_k: 4
                embedding_model: "BAAI/bge-small-en-v1.5"
                generator_provider: "groq"
                generator_model: "llama-3.3-70b-versatile"
                corpus_path: "docs/"
                """;

        PipelineConfigData config = fetcher.parseAndValidate(yaml);
        assertEquals("Custom prompt {query} {context}", config.promptTemplate());
        assertEquals(256, config.chunkSizeTokens());
        assertEquals(30, config.chunkOverlapTokens());
        assertEquals(15, config.retrievalTopK());
        assertEquals(4, config.rerankTopK());
        assertEquals("BAAI/bge-small-en-v1.5", config.embeddingModel());
        assertEquals("groq", config.generatorProvider());
        assertEquals("docs/", config.corpusPath());
    }

    @Test
    void parseAndValidate_rejectsUnauthorizedProvider() {
        String yaml = """
                generator_provider: "untrusted_cloud_llm"
                """;

        assertThrows(PipelineConfigValidationException.class, () ->
                fetcher.parseAndValidate(yaml)
        );
    }

    @Test
    void parseAndValidate_rejectsUnauthorizedEmbeddingModel() {
        String yaml = """
                embedding_model: "malicious/unverified-model"
                """;

        assertThrows(PipelineConfigValidationException.class, () ->
                fetcher.parseAndValidate(yaml)
        );
    }

    @Test
    void computeIndexHash_producesDeterministic64CharHex() {
        String hash1 = fetcher.computeIndexHash(512, 50, "sentence-transformers/all-MiniLM-L6-v2", "corpus-v1");
        String hash2 = fetcher.computeIndexHash(512, 50, "sentence-transformers/all-MiniLM-L6-v2", "corpus-v1");
        String hashDifferentCorpus = fetcher.computeIndexHash(512, 50, "sentence-transformers/all-MiniLM-L6-v2", "corpus-v2");

        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2, "Index hash must be deterministic");
        assertNotEquals(hash1, hashDifferentCorpus, "Different corpus version must produce different index hash");
    }

    @Test
    void computeConfigHash_includesEngineVersionAndEvaluationFields() {
        PipelineConfigData config = PipelineConfigData.defaultConfiguration();
        String hash1 = fetcher.computeConfigHash("sha-python-1", "cross-encoder/nli-deberta-v3-small", "ms-marco-MiniLM-L-6-v2", config, "v1");
        String hash2 = fetcher.computeConfigHash("sha-python-1", "cross-encoder/nli-deberta-v3-small", "ms-marco-MiniLM-L-6-v2", config, "v1");
        String hashEngineChanged = fetcher.computeConfigHash("sha-python-2", "cross-encoder/nli-deberta-v3-small", "ms-marco-MiniLM-L-6-v2", config, "v1");

        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
        assertNotEquals(hash1, hashEngineChanged, "Deploying new engine version must bust config_hash");
    }
}

