"""
corpus_fetcher.py
-----------------
Fetches corpus documents from GitHub repository at a specific commit SHA.
Used by IndexManager to build or refresh the Qdrant & BM25 indices on a cache miss.
"""

from __future__ import annotations
import logging
from pathlib import Path
import httpx

from app.config import settings
from app.evaluation.pipeline_config import CorpusRef

logger = logging.getLogger(__name__)


class CorpusFetchError(Exception):
    """Raised when fetching corpus files from GitHub fails."""
    pass


class CorpusFetcher:
    """
    Downloads corpus files from GitHub Contents API into a designated directory.
    Atomic: on any failure, removes partially downloaded files and raises CorpusFetchError.
    """

    def __init__(self, token: str | None = None) -> None:
        self._token = token or settings.github_token

    async def fetch(self, corpus_ref: CorpusRef, dest: Path) -> list[Path]:
        """
        Downloads files from corpus_ref.corpus_path at corpus_ref.ref_sha into dest.
        Raises CorpusFetchError on any failure — never returns partial results.
        """
        dest.mkdir(parents=True, exist_ok=True)
        headers = {
            "Accept": "application/vnd.github.v3+json",
            "User-Agent": "Sentinel-RAG-Evaluator",
        }
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"

        api_url = (
            f"https://api.github.com/repos/{corpus_ref.repo_full_name}/contents/"
            f"{corpus_ref.corpus_path.strip('/')}?ref={corpus_ref.ref_sha}"
        )

        downloaded_files: list[Path] = []
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.get(api_url, headers=headers)
                if resp.status_code != 200:
                    raise CorpusFetchError(
                        f"GitHub API returned {resp.status_code} for {api_url}: {resp.text}"
                    )

                items = resp.json()
                if not isinstance(items, list):
                    raise CorpusFetchError(
                        f"Expected list of items from {api_url}, got {type(items).__name__}"
                    )

                for item in items:
                    if item.get("type") != "file":
                        continue

                    download_url = item.get("download_url")
                    name = item.get("name")
                    if not download_url or not name:
                        continue

                    file_resp = await client.get(download_url, headers=headers)
                    if file_resp.status_code != 200:
                        raise CorpusFetchError(
                            f"Failed to download {name} from {download_url} (status {file_resp.status_code})"
                        )

                    file_path = dest / name
                    file_path.write_bytes(file_resp.content)
                    downloaded_files.append(file_path)

            if not downloaded_files:
                raise CorpusFetchError(f"No corpus files found at {corpus_ref.corpus_path}")

            logger.info("Successfully fetched %d corpus files to %s", len(downloaded_files), dest)
            return sorted(downloaded_files)

        except Exception as e:
            # Clean up partial files
            for p in downloaded_files:
                p.unlink(missing_ok=True)
            if isinstance(e, CorpusFetchError):
                raise
            raise CorpusFetchError(f"Error fetching corpus from GitHub: {e}") from e

