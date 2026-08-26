import requests
from app.config import settings

prompt = """You are a query expansion assistant for a retrieval system.
Given a user's question, generate exactly 3 alternative phrasings that
might retrieve relevant documents the original phrasing could miss.
Vary the wording, use synonyms, and consider different angles on the
same underlying question. Do not answer the question.

Return exactly 3 lines, one query per line, with no numbering, no
bullet points, and no extra commentary.

Original question: how do you stop a model from memorizing training data"""

for attempt in range(5):
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
    data = response.json()
    choice = data["choices"][0]
    print(f"--- attempt {attempt+1} ---")
    print("finish_reason:", choice.get("finish_reason"))
    print("content:", repr(choice["message"].get("content")))
    usage = data.get("usage", {})
    print("usage:", usage)
    print()