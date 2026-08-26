"""
Full end-to-end test of everything built so far:
ingest (already done, corpus is in Qdrant) -> self-correction retrieval
-> generation. This is the actual /query flow -> hallucination
guard, minus the FastAPI route wrapper.
"""

from app.correction.orchestrator import run_self_correction
from app.generation.generate_answer import generate_answer
from app.guard.hallucination_guard import check_answer


def run_query(query: str):
    print(f"\n{'='*70}")
    print(f"QUERY: {query!r}")
    print('='*70)

    result = run_self_correction(query)

    print(f"refused={result.refused} | retries={result.retry_count} | "
          f"score={result.context_quality_score:.3f}")

    if result.refused:
        print(f"\nFINAL ANSWER: Insufficient local context found to answer safely.")
        return

    context = "\n\n".join(c["text"] for c in result.chunks)
    raw_answer = generate_answer(query, context)

    guard_result = check_answer(raw_answer, context)

    print(f"\nGuard: is_clean={guard_result['is_clean']}, flagged={guard_result['flagged_entities']}")
    print(f"\nFINAL ANSWER:\n{guard_result['clean_answer']}")


if __name__ == "__main__":
    run_query("what is overfitting")
    run_query("explain gradient descent")