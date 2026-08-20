"""
fetch_corpus.py
----------------
Pulls a small, coherent set of Wikipedia articles (plain text, via the
official MediaWiki API) and saves each as a .txt file in data/corpus/.

This becomes the source corpus for:
  1. Ingestion testing (POST /ingest)
  2. Golden test-suite authoring (Person 3's task, Week 1)

Run:
    python scripts/fetch_corpus.py

Output:
    data/corpus/<slug>.txt      (one file per article)
    data/corpus/_manifest.json  (title -> filename + url, for reference)

Note: uses `requests` directly against the MediaWiki API rather than the
`wikipedia` PyPI package, which is unmaintained and breaks intermittently
against current API response shapes.
"""

import json
import re
import time
from pathlib import Path

import requests

API_URL = "https://en.wikipedia.org/w/api.php"

# Pick a topic cluster you personally understand well, so you can sanity
# check retrieval quality by eye. Swap this list for anything else.
TOPICS = [
    "Machine learning",
    "Supervised learning",
    "Unsupervised learning",
    "Neural network",
    "Deep learning",
    "Convolutional neural network",
    "Recurrent neural network",
    "Transformer (deep learning architecture)",
    "Natural language processing",
    "Reinforcement learning",
    "Overfitting",
    "Gradient descent",
    "Support vector machine",
    "Random forest",
    "Decision tree learning",
    "Feature engineering",
    "Cross-validation (statistics)",
    "Bias-variance tradeoff",
    "Word embedding",
    "Attention (machine learning)",
]

OUTPUT_DIR = Path(__file__).resolve().parent.parent / "data" / "corpus"
MIN_CHARS = 500  # skip stub/disambiguation-ish pages that are too short
HEADERS = {
    # Wikipedia asks all API clients to identify themselves.
    "User-Agent": "SentinelRAG-CorpusFetcher/1.0 (student project; contact: none)"
}


def slugify(title: str) -> str:
    slug = title.lower().strip()
    slug = re.sub(r"[^\w\s-]", "", slug)
    slug = re.sub(r"[\s]+", "_", slug)
    return slug


def fetch_one(title: str) -> dict | None:
    """Fetch a single article's plain-text extract via the MediaWiki API."""
    params = {
        "action": "query",
        "prop": "extracts|info",
        "explaintext": 1,      # plain text, not HTML
        "exlimit": 1,
        "redirects": 1,        # follow redirects (e.g. "ML" -> "Machine learning")
        "inprop": "url",
        "titles": title,
        "format": "json",
    }

    try:
        resp = requests.get(API_URL, params=params, headers=HEADERS, timeout=10)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        print(f"  [skip] '{title}' request failed: {e}")
        return None

    pages = data.get("query", {}).get("pages", {})
    if not pages:
        print(f"  [skip] '{title}' no pages returned")
        return None

    page = next(iter(pages.values()))
    if "missing" in page:
        print(f"  [skip] '{title}' page not found")
        return None

    content = page.get("extract", "")
    if len(content) < MIN_CHARS:
        print(f"  [skip] '{title}' too short ({len(content)} chars)")
        return None

    return {
        "title": page.get("title", title),
        "url": page.get("fullurl", f"https://en.wikipedia.org/wiki/{title.replace(' ', '_')}"),
        "content": content,
    }


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []

    print(f"Fetching {len(TOPICS)} articles into {OUTPUT_DIR} ...")
    for i, title in enumerate(TOPICS, 1):
        print(f"[{i}/{len(TOPICS)}] {title}")
        result = fetch_one(title)
        if result is None:
            continue

        slug = slugify(result["title"])
        filename = f"{slug}.txt"
        filepath = OUTPUT_DIR / filename
        filepath.write_text(result["content"], encoding="utf-8")

        manifest.append(
            {
                "title": result["title"],
                "filename": filename,
                "url": result["url"],
                "chars": len(result["content"]),
            }
        )

        time.sleep(0.3)  # be polite to the API

    manifest_path = OUTPUT_DIR / "_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print(f"\nDone. {len(manifest)}/{len(TOPICS)} articles saved.")
    print(f"Manifest: {manifest_path}")


if __name__ == "__main__":
    main()