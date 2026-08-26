"""
groq_provider.py
-----------------
Groq-backed implementation of GenerationProvider. This is the
"primary" provider per the architecture doc.
"""

import requests

from app.config import settings
from app.generation.provider_base import GenerationProvider


GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = "qwen/qwen3.6-27b"

ANSWER_PROMPT_TEMPLATE = """Answer the question using ONLY the context provided below.
If the context does not contain enough information to answer, say so
directly rather than guessing.

Context:
{context}

Question: {question}

Answer:"""

class GroqProvider(GenerationProvider):
    def generate(self, prompt: str, context: str) -> str:
        full_prompt = ANSWER_PROMPT_TEMPLATE.format(question=prompt, context=context)

        response = requests.post(
            GROQ_URL,
            headers={"Authorization": f"Bearer {settings.groq_api_key}"},
            json={
                "model": GROQ_MODEL,
                "messages": [{"role": "user", "content": full_prompt}],
                "temperature": 0.3,
                "max_tokens": 500,
                "reasoning_effort": "none",
            },
            timeout=15,
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"].strip()
