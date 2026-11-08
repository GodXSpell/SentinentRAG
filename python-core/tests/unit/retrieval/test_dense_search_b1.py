"""
test_dense_search_b1.py
-----------------------
Regression test for B-1: Dense search ranking inversion.
Qdrant with Distance.COSINE returns cosine similarity scores directly.
The bug `1 - hit.score` incorrectly inverted similarity into distance,
causing the most relevant chunks to receive the lowest scores.
"""

from unittest.mock import MagicMock
import pytest

from app.retrieval import dense_search


class MockPoint:
    """Mock Qdrant ScoredPoint."""
    def __init__(self, score: float, payload: dict) -> None:
        self.score = score
        self.payload = payload


def test_dense_search_preserves_cosine_similarity(monkeypatch: pytest.MonkeyPatch) -> None:
    """
    B-1 Regression Test:
    Assert that dense_search.search preserves the raw cosine similarity score returned by Qdrant,
    ensuring higher similarity hits retain higher scores and relative ranking is not inverted.
    """
    mock_client = MagicMock()
    mock_points = [
        MockPoint(
            score=0.92,
            payload={
                "text": "Highly relevant chunk",
                "source_doc": "doc_a.md",
                "section_title": "Overview",
                "chunk_index": 0,
            },
        ),
        MockPoint(
            score=0.41,
            payload={
                "text": "Marginally relevant chunk",
                "source_doc": "doc_b.md",
                "section_title": "Details",
                "chunk_index": 1,
            },
        ),
    ]

    mock_query_result = MagicMock()
    mock_query_result.points = mock_points
    mock_client.query_points.return_value = mock_query_result

    monkeypatch.setattr(dense_search, "_get_client", lambda: mock_client)

    results = dense_search.search(query_vector=[0.1, 0.2, 0.3], top_k=2)

    assert len(results) == 2

    # High similarity hit (0.92) must have score 0.92, NOT 1 - 0.92 = 0.08
    assert results[0]["score"] == pytest.approx(0.92, abs=1e-5)
    # Lower similarity hit (0.41) must have score 0.41, NOT 1 - 0.41 = 0.59
    assert results[1]["score"] == pytest.approx(0.41, abs=1e-5)

    # Higher relevance must have strictly higher score
    assert results[0]["score"] > results[1]["score"]

