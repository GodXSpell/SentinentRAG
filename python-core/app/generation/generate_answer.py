"""
generate_answer.py
--------------------
Picks a provider and generates an answer, with automatic fallback:
try Groq first (primary), fall back to Ollama (offline) if Groq fails.
"""

from __future__ import annotations
import logging
from app.generation.groq_provider import GroqProvider
from app.generation.ollama_provider import OllamaProvider
from app.evaluation.pipeline_config import PipelineConfig

logger = logging.getLogger(__name__)

_groq = GroqProvider()
_ollama = OllamaProvider()


def generate_answer(
    prompt: str,
    context: str,
    config: PipelineConfig | None = None,
) -> str:
    """
    Generates an answer using the configured provider and prompt template.
    If config is provided, uses its specified generator_provider.
    Otherwise attempts primary provider (Groq) with fallback to Ollama.
    """
    if config is not None:
        provider = config.generator_provider.lower().strip()
        if provider == "ollama":
            return _ollama.generate(prompt, context)
        elif provider == "groq":
            return _groq.generate(prompt, context)

    try:
        return _groq.generate(prompt, context)
    except Exception as groq_exception:
        logger.warning(f"[generate_answer] Groq failed: {groq_exception}. Falling back to Ollama.")
        try:
            return _ollama.generate(prompt, context)
        except Exception as ollama_exception:
            logger.error(f"[generate_answer] Ollama also failed: {ollama_exception}. No answer generated.")
            return "[ERROR] Unable to generate an answer, both generations failed."
