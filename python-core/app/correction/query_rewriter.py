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

import requests

from app.config import settings

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = "openai/gpt-oss-20b"  # that small fucking model was deprecated and I wasted my fucking time on it, so now we use the big one. it costs more but it's worth it.

REWRITE_PROMPT_TEMPLATE = """
    You are a query expansion assistant for a retrieval system.
    Given a user's question, generate exactly 3 alternative phrasings that
    might retrieve relevant documents the original phrasing could miss.
    Vary the wording, use synonyms, and consider different angles on the
    same underlying question. Do not answer the question.

    Return exactly 3 lines, one query per line, with no numbering, no
    bullet points, and no extra commentary.

    Original question: {query}
    """

def rewrite_query(query: str) -> list[dict]:
    """
        Returns a list of 3 alternative query strings. Falls back to
        returning [query] repeated 3 times if the API call fails or
        the response can't be parsed into exactly 3 lines - this keeps
        the orchestrator's retry loop functioning even if generation
        has a transient failure, rather than crashing the whole request.
    """
    prompt = REWRITE_PROMPT_TEMPLATE.format(query=query)

    try:
        response = requests.post(
            GROQ_URL,
            headers={
                "Authorization": f"Bearer {settings.groq_api_key}",
                "Content-Type": "application/json"
            },
            json={
                "model": GROQ_MODEL,
                "messages": [
                    {"role": "user", "content": prompt},
                ],
                "temperature": 0.7,
                "max_tokens": 200,
                },
            timeout=10
        )
        response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
    except Exception as e:
        print(f"[query_rewriter] API call failed: {e}")
        return [{"query": query}] * 3

    lines = [line.strip() for line in content.strip().split("\n") if line.strip()]

    if len(lines) != 3:
        print(
            f"[query_rewriter] expected 3 lines, got {len(lines)}; "
            f"falling back to original query"
        )
        return [query] * 3

    return lines