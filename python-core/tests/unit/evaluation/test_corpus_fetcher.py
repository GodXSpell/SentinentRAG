"""
test_corpus_fetcher.py
----------------------
Unit tests for CorpusFetcher.
"""

from pathlib import Path
import httpx
import pytest

from app.evaluation.corpus_fetcher import CorpusFetcher, CorpusFetchError
from app.evaluation.pipeline_config import CorpusRef


@pytest.mark.anyio
async def test_corpus_fetcher_success(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    corpus_ref = CorpusRef(
        repo_full_name="GodXSpell/SentinentRAG",
        ref_sha="abcdef123456",
        corpus_path="docs/corpus",
    )

    contents_response = [
        {
            "type": "file",
            "name": "doc1.md",
            "download_url": "https://raw.githubusercontent.com/dummy/doc1.md",
        },
        {
            "type": "file",
            "name": "doc2.md",
            "download_url": "https://raw.githubusercontent.com/dummy/doc2.md",
        },
        {
            "type": "dir",
            "name": "subdir",
            "download_url": None,
        },
    ]

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        url_str = str(request.url)
        if "contents/docs/corpus" in url_str:
            return httpx.Response(200, json=contents_response)
        elif "doc1.md" in url_str:
            return httpx.Response(200, content=b"# Document 1 Content")
        elif "doc2.md" in url_str:
            return httpx.Response(200, content=b"# Document 2 Content")
        return httpx.Response(404)

    transport = httpx.MockTransport(mock_handler)
    orig_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: orig_client(transport=transport))

    fetcher = CorpusFetcher(token="test-token")
    files = await fetcher.fetch(corpus_ref, tmp_path)

    assert len(files) == 2
    assert (tmp_path / "doc1.md").read_text() == "# Document 1 Content"
    assert (tmp_path / "doc2.md").read_text() == "# Document 2 Content"


@pytest.mark.anyio
async def test_corpus_fetcher_api_error(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    corpus_ref = CorpusRef(
        repo_full_name="GodXSpell/SentinentRAG",
        ref_sha="bad_sha",
        corpus_path="docs/corpus",
    )

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, text="Not Found")

    transport = httpx.MockTransport(mock_handler)
    orig_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: orig_client(transport=transport))

    fetcher = CorpusFetcher()
    with pytest.raises(CorpusFetchError, match="GitHub API returned 404"):
        await fetcher.fetch(corpus_ref, tmp_path)


@pytest.mark.anyio
async def test_corpus_fetcher_cleans_up_on_partial_failure(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    corpus_ref = CorpusRef(
        repo_full_name="GodXSpell/SentinentRAG",
        ref_sha="partially_broken",
        corpus_path="docs/corpus",
    )

    contents_response = [
        {"type": "file", "name": "doc1.md", "download_url": "https://raw.githubusercontent.com/dummy/doc1.md"},
        {"type": "file", "name": "broken.md", "download_url": "https://raw.githubusercontent.com/dummy/broken.md"},
    ]

    async def mock_handler(request: httpx.Request) -> httpx.Response:
        url_str = str(request.url)
        if "contents/docs/corpus" in url_str:
            return httpx.Response(200, json=contents_response)
        elif "doc1.md" in url_str:
            return httpx.Response(200, content=b"# Document 1 Content")
        elif "broken.md" in url_str:
            return httpx.Response(500, text="Internal Server Error")
        return httpx.Response(404)

    transport = httpx.MockTransport(mock_handler)
    orig_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: orig_client(transport=transport))

    fetcher = CorpusFetcher()
    with pytest.raises(CorpusFetchError, match="Failed to download broken.md"):
        await fetcher.fetch(corpus_ref, tmp_path)

    # doc1.md must have been deleted to avoid partial corrupt state
    assert not (tmp_path / "doc1.md").exists()
