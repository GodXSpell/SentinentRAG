from app.correction import orchestrator
from app.correction import models

# --- Test 1: exercise _retry_retrieval directly ---
print("=== Testing _retry_retrieval() directly ===")
result = orchestrator._retry_retrieval("what is overfitting")
print(f"{len(result)} chunks returned from retry retrieval")
for r in result[:3]:
    print(f"  rerank_score={r['rerank_score']:.3f} | {r['section_title']} | {r['text'][:60]}")

# --- Test 2: force the FULL loop through the RETRY branch ---
print("\n=== Forcing full run_self_correction() through RETRY ===")

original_direct = models.DIRECT_THRESHOLD
models.DIRECT_THRESHOLD = 0.99999  # near-impossible to hit on the first pass

try:
    result = orchestrator.run_self_correction("explain gradient descent")
    print(f"refused={result.refused} retries={result.retry_count} score={result.context_quality_score:.3f}")
    for line in result.trace:
        print(f"  {line}")
    if not result.refused:
        print(f"  Top chunk after retry: {result.chunks[0]['section_title']} - {result.chunks[0]['text'][:80]}")
finally:
    models.DIRECT_THRESHOLD = original_direct
    print(f"\nDIRECT_THRESHOLD restored to {models.DIRECT_THRESHOLD}")