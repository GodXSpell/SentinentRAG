package com.sentinel.rag.pipeline;

/**
 * Validated pipeline configuration extracted from sentinel.yaml per Plan §1.2 & §3.2.
 */
public record PipelineConfigData(
        String promptTemplate,
        int chunkSizeTokens,
        int chunkOverlapTokens,
        int retrievalTopK,
        int rerankTopK,
        String embeddingModel,
        String generatorProvider,
        String generatorModel,
        String corpusPath
) {
    public static final String DEFAULT_PROMPT_TEMPLATE =
            "Answer the following question based only on the provided context.\n\nContext:\n{context}\n\nQuestion: {query}\n\nAnswer:";
    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_CHUNK_OVERLAP = 50;
    public static final int DEFAULT_RETRIEVAL_TOP_K = 20;
    public static final int DEFAULT_RERANK_TOP_K = 5;
    public static final String DEFAULT_EMBEDDING_MODEL = "sentence-transformers/all-MiniLM-L6-v2";
    public static final String DEFAULT_GENERATOR_PROVIDER = "groq";
    public static final String DEFAULT_GENERATOR_MODEL = "llama-3.3-70b-versatile";
    public static final String DEFAULT_CORPUS_PATH = "corpus/";

    public static PipelineConfigData defaultConfiguration() {
        return new PipelineConfigData(
                DEFAULT_PROMPT_TEMPLATE,
                DEFAULT_CHUNK_SIZE,
                DEFAULT_CHUNK_OVERLAP,
                DEFAULT_RETRIEVAL_TOP_K,
                DEFAULT_RERANK_TOP_K,
                DEFAULT_EMBEDDING_MODEL,
                DEFAULT_GENERATOR_PROVIDER,
                DEFAULT_GENERATOR_MODEL,
                DEFAULT_CORPUS_PATH
        );
    }
}

