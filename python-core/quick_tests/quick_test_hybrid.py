# quick_test_hybrid.py
from app.retrieval.hybrid import hybrid_search

results = hybrid_search("what is overfitting", dense_top_k=10, sparse_top_k=10)
for r in results[:5]:
    print(f"{r['rrf_score']:.4f} | {r['section_title']:25} | {r['text'][:80]}")