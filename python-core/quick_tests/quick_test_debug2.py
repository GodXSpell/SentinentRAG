# quick_test_debug2.py
from app.correction.query_rewriter import rewrite_query
import requests
from app.config import settings

# Bypass rewrite_query's silent fallback to see the RAW response
prompt = f"""You are a query expansion assistant for a retrieval system.
Given a user's question, generate exactly 3 alternative phrasings that
might retrieve relevant documents the original phrasing could miss.
Vary the wording, use synonyms, and consider different angles on the
same underlying question. Do not answer the question.

Return exactly 3 lines, one query per line, with no numbering, no
bullet points, and no extra commentary.

Original question: how do you stop a model from memorizing training data"""

response = requests.post(
    "https://api.groq.com/openai/v1/chat/completions",
    headers={"Authorization": f"Bearer {settings.groq_api_key}"},
    json={
        "model": "openai/gpt-oss-20b",
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.7,
        "max_tokens": 200,
    },
    timeout=10,
)
print("STATUS:", response.status_code)
print("RAW CONTENT:")
print(repr(response.json()["choices"][0]["message"]["content"]))