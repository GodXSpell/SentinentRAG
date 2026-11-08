"""
test_hallucination_guard_b2.py
------------------------------
Regression test for B-2: Typo in hallucination_guard.py (`normlaized_context`).
Verifies clean variable naming and ensures deterministic entity checking correctly
verifies supported claims vs unsupported claims.
"""

import inspect
from app.guard import hallucination_guard


def test_hallucination_guard_no_misspelled_variable() -> None:
    """
    B-2 Regression Test (Static):
    Ensure the misspelled variable `normlaized_context` is completely eradicated
    from `app.guard.hallucination_guard`.
    """
    source = inspect.getsource(hallucination_guard.check_answer)
    assert "normlaized_context" not in source, (
        "Found misspelled identifier 'normlaized_context' in check_answer"
    )
    assert "normalized_context" in source, (
        "Expected clean identifier 'normalized_context' in check_answer"
    )


def test_hallucination_guard_supported_and_unsupported_entities() -> None:
    """
    B-2 Functional Test:
    Entities present in context must be preserved as clean.
    Entities absent from context must be flagged and redacted.
    """
    context = "Sentinel-RAG was released in 2024 and achieved 95% precision."
    
    # 1. Answer with supported entities
    supported_answer = "Sentinel-RAG had 95% precision in 2024."
    result_supported = hallucination_guard.check_answer(supported_answer, context)
    assert result_supported["is_clean"] is True
    assert len(result_supported["flagged_entities"]) == 0
    assert result_supported["clean_answer"] == supported_answer

    # 2. Answer with an unsupported hallucinated entity (date 1999 not in context)
    hallucinated_answer = "Sentinel-RAG was released in 1999."
    result_hallucinated = hallucination_guard.check_answer(hallucinated_answer, context)
    assert result_hallucinated["is_clean"] is False
    assert any("1999" in text for text, _ in result_hallucinated["flagged_entities"])
    assert "[unsupported claim removed]" in result_hallucinated["clean_answer"]

