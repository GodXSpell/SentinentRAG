from app.correction.orchestrator import run_self_correction

queries = [
    "how do you stop a model from memorizing training data",
    "what happens when a model is too complex"
    # "difference between bias and variance tradeoffs in practice",
    # "what is a neural network",
    # "what color is the sky",
    # "explain gradient descent"
]

for query in queries:
    print(f"\n=== Query: {query!r} ===")
    result = run_self_correction(query)
    print(f"refused={result.refused} retries={result.retry_count} score={result.context_quality_score:.3f}")
    for line in result.trace:
        print(f"  {line}")
    if result.refused:
        print(f"  Refusal reason: {result.refusal_reason}")
    else:
        print(f"  Top chunk: {result.chunks[0]['section_title']} - {result.chunks[0]['text'][:80]}")