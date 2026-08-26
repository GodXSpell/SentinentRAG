# quick_test_debug6.py
from app.retrieval import sparse_search

queries_to_test = [
    "how do you stop a model from memorizing training data",  # original
    "techniques to prevent machine learning models from overfitting",  # rewrite 1
    "methods to reduce data memorization in neural networks",  # rewrite 2
]

for q in queries_to_test:
    print(f"\n=== BM25 search: {q!r} ===")
    results = sparse_search.search(q, top_k=5)
    for r in results:
        print(f"  score={r['score']:.3f} | {r['section_title']:20} | {r['text'][:60]}")