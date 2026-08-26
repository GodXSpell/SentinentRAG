"""
ollama_provider.py
--------------------
Ollama-backed implementation of GenerationProvider - local, offline
fallback per the architecture doc, used when Groq is unavailable.
"""

import requests

from app.config import settings
from app.generation.provider_base import GenerationProvider

OLLAMA_MODEL = "llama3.2"  # adjust to whatever model you've pulled locally

ANSWER_PROMPT_TEMPLATE = """Answer the question using ONLY the context provided below.
If the context does not contain enough information to answer, say so
directly rather than guessing.

Context:
{context}

Question: {question}

Answer:"""


class OllamaProvider(GenerationProvider):
    def generate(self, prompt: str, context: str) -> str:
        full_prompt = ANSWER_PROMPT_TEMPLATE.format(context=context, question=prompt)

        response = requests.post(
            f"{settings.ollama_base_url}/api/generate",
            json={
                "model": OLLAMA_MODEL,
                "prompt": full_prompt,
                "stream": False,
            },
            timeout=30,
        )
        response.raise_for_status()
        return response.json()["response"].strip()