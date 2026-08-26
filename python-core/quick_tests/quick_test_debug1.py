# quick_test_debug1.py
from app.retrieval.pipeline import retrieve

results = retrieve("how do you stop a model from memorizing training data")
for r in results:
    print(f"{r['rerank_score']:.3f} | {r['section_title']:25} | {r['text'][:80]}")