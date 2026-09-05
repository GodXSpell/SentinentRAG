package com.sentinel.rag.cache;

/**
 * The subset of evaluation configuration that, if changed, should invalidate cached
 * eval scores. Kept as an explicit record (not a raw Map<String,String>) on purpose:
 * every field here is something a compiler-checked call site has to supply, so adding
 * a new hash-relevant parameter later is a one-line change instead of a silent typo
 * risk in a string key.
 *
 * Starting set — adjust as the eval pipeline grows:
 *  - evaluatorModel:   which local NLI/Ragas evaluator produced the score (e.g. "local-nli-v1").
 *                      Swapping evaluator models should always invalidate old cache entries,
 *                      since scores from different evaluators are not comparable.
 *  - rerankerModel:    which cross-encoder reranked the context before evaluation.
 *  - retrievalTopK:    how many chunks were retrieved before reranking down to top 5 (FR-2.2).
 *                      Changing this changes what evidence the evaluator saw, even if the
 *                      final top-5 chunks look the same.
 *
 * Deliberately NOT included: prompt template and query and context chunk are hashed as
 * their own separate components in SemanticCacheService — don't duplicate them here.
 */
public record ConfigParams(
        String evaluatorModel,
        String rerankerModel,
        int retrievalTopK
) {
    /**
     * Canonical string form fed into the hash. Field order here is fixed and must never
     * change once real cache entries exist in production — reordering fields changes
     * every future hash for the same logical config, silently missing every existing
     * cache entry (not incorrect, just a full one-time cache flush you didn't ask for).
     */
    public String toCanonicalString() {
        return evaluatorModel + "|" + rerankerModel + "|" + retrievalTopK;
    }
}