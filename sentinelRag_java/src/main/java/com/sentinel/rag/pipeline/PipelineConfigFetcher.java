package com.sentinel.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.rag.cache.CacheKeyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Fetches and validates sentinel.yaml from the PR head SHA or local fallback per Plan §3.2.
 * Enforces security allowlists on models and providers.
 * Computes canonical index_hash and config_hash.
 */
@Service
public class PipelineConfigFetcher {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigFetcher.class);

    public static final Set<String> ALLOWED_GENERATOR_PROVIDERS = Set.of("groq", "ollama", "openai");
    public static final Set<String> ALLOWED_EMBEDDING_MODELS = Set.of(
            "BAAI/bge-small-en-v1.5",
            "sentence-transformers/all-MiniLM-L6-v2"
    );
    public static final Set<String> ALLOWED_RERANKER_MODELS = Set.of(
            "cross-encoder/ms-marco-MiniLM-L-6-v2"
    );

    private final ObjectMapper objectMapper;
    private final String githubToken;
    private final HttpClient httpClient;

    public PipelineConfigFetcher(
            ObjectMapper objectMapper,
            @Value("${sentinel.github.token:}") String githubToken
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.githubToken = githubToken != null ? githubToken.trim() : "";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Fetches and parses sentinel.yaml for the given repository and commit SHA.
     * Falls back to root sentinel.yaml or default configuration if fetch fails or runs offline.
     */
    public PipelineConfigData fetchAndValidate(String repoOwnerSlashName, String commitSha) {
        String yamlContent = fetchRawYaml(repoOwnerSlashName, commitSha);
        if (yamlContent == null || yamlContent.isBlank()) {
            log.info("No remote sentinel.yaml found for {}@{}; using local/default configuration", repoOwnerSlashName, commitSha);
            yamlContent = loadLocalSentinelYaml();
        }

        if (yamlContent == null || yamlContent.isBlank()) {
            return PipelineConfigData.defaultConfiguration();
        }

        return parseAndValidate(yamlContent);
    }

    /**
     * Parses raw YAML content and validates all parameters against security allowlists.
     */
    public PipelineConfigData parseAndValidate(String yamlContent) {
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(yamlContent);
        if (map == null) {
            return PipelineConfigData.defaultConfiguration();
        }

        String promptTemplate = (String) map.getOrDefault("prompt_template", PipelineConfigData.DEFAULT_PROMPT_TEMPLATE);
        int chunkSize = getInt(map, "chunk_size_tokens", PipelineConfigData.DEFAULT_CHUNK_SIZE);
        int chunkOverlap = getInt(map, "chunk_overlap_tokens", PipelineConfigData.DEFAULT_CHUNK_OVERLAP);
        int retrievalTopK = getInt(map, "retrieval_top_k", PipelineConfigData.DEFAULT_RETRIEVAL_TOP_K);
        int rerankTopK = getInt(map, "rerank_top_k", PipelineConfigData.DEFAULT_RERANK_TOP_K);
        String embeddingModel = (String) map.getOrDefault("embedding_model", PipelineConfigData.DEFAULT_EMBEDDING_MODEL);
        String generatorProvider = (String) map.getOrDefault("generator_provider", PipelineConfigData.DEFAULT_GENERATOR_PROVIDER);
        String generatorModel = (String) map.getOrDefault("generator_model", PipelineConfigData.DEFAULT_GENERATOR_MODEL);
        String corpusPath = (String) map.getOrDefault("corpus_path", PipelineConfigData.DEFAULT_CORPUS_PATH);

        // Security Allowlists validation
        if (!ALLOWED_GENERATOR_PROVIDERS.contains(generatorProvider.toLowerCase())) {
            throw new PipelineConfigValidationException(
                    "Unauthorized generator_provider: '" + generatorProvider + "'. Allowed: " + ALLOWED_GENERATOR_PROVIDERS
            );
        }

        if (!ALLOWED_EMBEDDING_MODELS.contains(embeddingModel)) {
            throw new PipelineConfigValidationException(
                    "Unauthorized embedding_model: '" + embeddingModel + "'. Allowed: " + ALLOWED_EMBEDDING_MODELS
            );
        }

        return new PipelineConfigData(
                promptTemplate,
                chunkSize,
                chunkOverlap,
                retrievalTopK,
                rerankTopK,
                embeddingModel,
                generatorProvider,
                generatorModel,
                corpusPath
        );
    }

    /**
     * Computes index_hash = SHA-256(canonical JSON of {chunk_size_tokens, chunk_overlap_tokens, embedding_model, corpus_version})
     */
    public String computeIndexHash(int chunkSize, int chunkOverlap, String embeddingModel, String corpusVersion) {
        Map<String, Object> canonicalMap = new TreeMap<>();
        canonicalMap.put("chunk_overlap_tokens", chunkOverlap);
        canonicalMap.put("chunk_size_tokens", chunkSize);
        canonicalMap.put("corpus_version", corpusVersion);
        canonicalMap.put("embedding_model", embeddingModel);

        try {
            String json = objectMapper.writeValueAsString(canonicalMap);
            return HexFormat.of().formatHex(CacheKeyBuilder.sha256(json));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize canonical index JSON", e);
        }
    }

    /**
     * Computes config_hash = SHA-256(canonical JSON of all 12 pipeline & engine attributes).
     */
    public String computeConfigHash(
            String engineVersion,
            String evaluatorVersion,
            String rerankerModel,
            PipelineConfigData config,
            String corpusVersion
    ) {
        Map<String, Object> canonicalMap = new TreeMap<>();
        canonicalMap.put("chunk_overlap_tokens", config.chunkOverlapTokens());
        canonicalMap.put("chunk_size_tokens", config.chunkSizeTokens());
        canonicalMap.put("corpus_version", corpusVersion);
        canonicalMap.put("embedding_model", config.embeddingModel());
        canonicalMap.put("engine_version", engineVersion);
        canonicalMap.put("evaluator_version", evaluatorVersion);
        canonicalMap.put("generator_model", config.generatorModel());
        canonicalMap.put("generator_provider", config.generatorProvider());
        canonicalMap.put("prompt_template", config.promptTemplate());
        canonicalMap.put("rerank_top_k", config.rerankTopK());
        canonicalMap.put("reranker_model", rerankerModel);
        canonicalMap.put("retrieval_top_k", config.retrievalTopK());

        try {
            String json = objectMapper.writeValueAsString(canonicalMap);
            return HexFormat.of().formatHex(CacheKeyBuilder.sha256(json));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize canonical config JSON", e);
        }
    }

    private String fetchRawYaml(String repo, String sha) {
        if (githubToken.isBlank() || repo == null || sha == null) {
            return null;
        }

        String url = String.format("https://api.github.com/repos/%s/contents/sentinel.yaml?ref=%s", repo, sha);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github.v3.raw")
                    .header("User-Agent", "Sentinel-RAG-ControlPlane")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.warn("Could not fetch remote sentinel.yaml via GitHub API: {}", e.getMessage());
        }
        return null;
    }

    private String loadLocalSentinelYaml() {
        File[] candidateFiles = new File[]{
                new File("sentinel.yaml"),
                new File("../sentinel.yaml"),
                new File("src/main/resources/sentinel.yaml")
        };

        for (File file : candidateFiles) {
            if (file.exists() && file.isFile()) {
                try (InputStream is = new FileInputStream(file)) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}

