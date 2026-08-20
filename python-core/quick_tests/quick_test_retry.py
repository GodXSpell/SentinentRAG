from app.correction import orchestrator


def fake_rewrite_query(query):
    print(f"\nrewrite_query called with: {query!r}")
    return [
        {"query": "overfitting regularization"},
        {"query": "prevent model memorization"},
        {"query": "training validation generalization"},
    ]


def fake_sparse_search(query, top_k):
    print(f"sparse_search called with: {query!r}, top_k={top_k}")

    return [
        {
            "source_doc": f"doc_{query}",
            "chunk_index": 0,
            "text": f"Result for {query}",
            "score": 0.6,
        }
    ]


def fake_rerank(query, candidates, top_k):
    print(f"\nrerank called with original query: {query!r}")
    print(f"candidate count: {len(candidates)}")
    print(f"top_k: {top_k}")

    return [
        {
            "source_doc": "doc_best",
            "chunk_index": 0,
            "text": "Regularization helps prevent overfitting.",
            "score": 0.82,
        }
    ]


def main():
    orchestrator.rewrite_query = fake_rewrite_query
    orchestrator.sparse_search.search = fake_sparse_search
    orchestrator.rerank = fake_rerank

    result = orchestrator._retry_retrieval(
        "what is overfitting"
    )

    print("\n=== RETRY DIAGNOSTIC ===")
    print(f"Returned chunks: {len(result)}")
    print(f"Result: {result}")


if __name__ == "__main__":
    main()