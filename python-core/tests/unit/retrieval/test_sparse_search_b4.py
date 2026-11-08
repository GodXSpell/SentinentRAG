"""
test_sparse_search_b4.py
------------------------
Regression test for B-4: Default argument top_k=settings.sparse_top_k evaluated at import time.
Ensures that runtime / test configuration changes to settings.sparse_top_k are respected
when search() is invoked without an explicit top_k argument.
"""

from unittest.mock import MagicMock
import pytest

from app.config import settings
from app.retrieval import sparse_search


def test_sparse_search_respects_dynamic_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    """
    B-4 Regression Test:
    Verify that monkeypatching settings.sparse_top_k after import time changes
    the effective default limit of sparse_search.search().
    """
    # Create 5 dummy chunks
    mock_chunks = [
        {"text": f"chunk {i}", "source_doc": "doc.md", "section_title": "sec", "chunk_index": i}
        for i in range(5)
    ]
    mock_bm25 = MagicMock()
    # Scores: 5.0, 4.0, 3.0, 2.0, 1.0
    mock_bm25.get_scores.return_value = [5.0, 4.0, 3.0, 2.0, 1.0]

    monkeypatch.setattr(sparse_search, "_bm25_index", mock_bm25)
    monkeypatch.setattr(sparse_search, "_bm25_chunks", mock_chunks)

    # Monkeypatch settings.sparse_top_k to 2 at runtime
    monkeypatch.setattr(settings, "sparse_top_k", 2)

    # Invoke search without explicit top_k
    results = sparse_search.search("test query")

    # If top_k was evaluated at import time (where default was 20),
    # results would contain all 5 items. With the fix, it must return exactly 2.
    assert len(results) == 2
    assert results[0]["chunk_index"] == 0
    assert results[1]["chunk_index"] == 1

