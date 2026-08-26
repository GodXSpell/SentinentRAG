"""
query_rewriter.py
------------------
QueryRewriterAgent (FR-2.4): given the original query, generates 3
expanded/alternative sub-queries to try when the first retrieval
attempt scored in the middle band.

NOTE: this makes a direct Groq API call inline for now, ahead of the
full generation/provider_base.py abstraction (Day 5 work, pulled
forward). Once that abstraction exists, refactor this to call through
generation/groq_provider.py instead of duplicating the HTTP call here.
"""

import re
import requests
from app.config import settings

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = "qwen/qwen3.6-27b"

REWRITE_PROMPT_TEMPLATE = """You are a query expansion assistant for a retrieval system.
    Given a user's question, generate exactly 3 alternative phrasings that
    might retrieve relevant documents the original phrasing could miss.
    Vary the wording, use synonyms, and consider different angles on the
    same underlying question. Do not answer the question.

    Return exactly 3 lines, one query per line, with no numbering, no
    bullet points, and no extra commentary.

    Original question: {query}"""

# Strips common LLM list-formatting noise from the start of a line:
# "1. ", "1) ", "- ", "* ", "• "
_LEADING_MARKER_RE = re.compile(r"^\s*(\d+[.)]|[-*•])\s+")


def _call_groq(query: str) -> str | None:
    """
        Makes one Groq API call. Returns the raw response content,
        or None if the call itself failed (network, auth, rate limit).
    """
    prompt = REWRITE_PROMPT_TEMPLATE.format(query=query)
    try:
        response = requests.post(
            GROQ_URL,
            headers={"Authorization": f"Bearer {settings.groq_api_key}"},
            json={
                "model": GROQ_MODEL,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.7,
                "max_tokens": 300,
                "reasoning_effort": "none",
                "reasoning_format": "hidden",
            },
            timeout=10,
        )
        if not response.ok:
            print(f"[query_rewriter] Groq error {response.status_code}: {response.text}")
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]
    except Exception as e:
        print(f"[query_rewriter] Groq call failed: {e}")
        return None


def _parse_lines(content: str) -> list[str]:
    """
        Splits raw LLM content into cleaned lines, stripping common
        list-formatting noise (numbering, bullets) and blank lines.
    """
    lines = []
    for raw_line in content.strip().split("\n"):
        cleaned = _LEADING_MARKER_RE.sub("", raw_line).strip()
        if cleaned:
            lines.append(cleaned)
    return lines


def rewrite_query(query: str) -> list[str]:
    """
        Returns a list of 3 alternative query strings. Makes up to 2
        attempts at the Groq call (LLM formatting is non-deterministic -
        a retry often succeeds where the first attempt didn't). Falls
        back to [query, query, query] only if both attempts fail to
        produce exactly 3 usable lines, or the API call itself fails
        both times - this keeps the orchestrator's retry loop functioning
        even if generation has a persistent failure.
    """
    for attempt in (1, 2):
        content = _call_groq(query)
        if content is None:
            continue  # network/API failure, try again

        lines = _parse_lines(content)
        if len(lines) == 3:
            return lines

        print(
            f"[query_rewriter] attempt {attempt}: expected 3 lines, "
            f"got {len(lines)}; {'retrying' if attempt == 1 else 'giving up'}"
        )

    return [query, query, query]