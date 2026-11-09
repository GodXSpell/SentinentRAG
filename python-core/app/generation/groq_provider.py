"""
groq_provider.py
-----------------
Groq-backed implementation of GenerationProvider.
"""

import logging
import re
import time
import requests

from app.config import settings
from app.generation.provider_base import GenerationProvider

logger = logging.getLogger(__name__)

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

PRIMARY_MODELS = [
    "qwen/qwen3.8-27b",
    "openai/gpt-oss-20b",
    "openai/gpt-oss-120b",
]

ANSWER_PROMPT_TEMPLATE = """Answer the question using ONLY the context provided below.
If the context does not contain enough information to answer, say so
directly rather than guessing.

Context:
{context}

Question: {question}

Answer:"""

class GroqProvider(GenerationProvider):
    def __init__(self, model_name: str | None = None) -> None:
        self.model_name = model_name

    def generate(self, prompt: str, context: str, model: str | None = None) -> str:
        full_prompt = ANSWER_PROMPT_TEMPLATE.format(question=prompt, context=context)
        models_to_try = []
        if model and model not in models_to_try:
            models_to_try.append(model)
        if self.model_name and self.model_name not in models_to_try:
            models_to_try.append(self.model_name)
        for m in PRIMARY_MODELS:
            if m not in models_to_try:
                models_to_try.append(m)

        for m in models_to_try:
            for attempt in range(4):
                try:
                    response = requests.post(
                        GROQ_URL,
                        headers={"Authorization": f"Bearer {settings.groq_api_key}"},
                        json={
                            "model": m,
                            "messages": [{"role": "user", "content": full_prompt}],
                            "temperature": 0.3,
                            "max_tokens": 500,
                        },
                        timeout=25,
                    )
                    if response.status_code == 200:
                        return response.json()["choices"][0]["message"]["content"].strip()

                    if response.status_code == 429:
                        text_err = response.text
                        m_wait = re.search(r"try again in ([0-9.]+)s", text_err, re.IGNORECASE)
                        wait_sec = float(m_wait.group(1)) if m_wait else 15.0
                        wait_sec = min(max(wait_sec, 2.0), 25.0)
                        logger.warning(f"Groq 429 hit for {m}. Sleeping {wait_sec:.1f}s...")
                        time.sleep(wait_sec)
                        continue

                    logger.warning(f"Groq error for {m} ({response.status_code}): {response.text}")
                    break
                except Exception as e:
                    logger.warning(f"Groq request exception for {m}: {e}")
                    time.sleep(2.0)

        raise RuntimeError("All Groq model attempts failed.")
