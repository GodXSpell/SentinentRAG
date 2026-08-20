# quick_test_pipeline.py
from app.retrieval.pipeline import retrieve

results = retrieve("what is tensors in machine learning")
for r in results:
    print(f"{r['rerank_score']:.4f} | {r['section_title']:25} | {r['text'][:80]}")