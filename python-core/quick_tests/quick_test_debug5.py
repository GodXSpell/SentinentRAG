# quick_test_debug5.py
from app.correction.orchestrator import _retry_retrieval
from app.correction.query_rewriter import rewrite_query

query = "how do you stop a model from memorizing training data"

print("=== Rewritten queries ===")
rewrites = rewrite_query(query)
for r in rewrites:
    print(f"  {r}")

print("\n=== _retry_retrieval result ===")
result = _retry_retrieval(query)
for r in result:
    print(f"  rerank_score={r['rerank_score']:.3f} | {r['section_title']} | {r['text'][:70]}")