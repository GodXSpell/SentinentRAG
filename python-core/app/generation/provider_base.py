"""
provider_base.py
-----------------
Abstract interface for LLM generation providers. Groq (primary) and
Ollama (offline fallback) both implement this, so the rest of the app
calls `generate()` without caring which provider is behind it.
"""

from abc import ABC, abstractmethod

class GenerationProvider(ABC):
    @abstractmethod
    def generate(self, prompt: str, context: str) -> str:
        """
            Generate an answer given a prompt (the user's question) and
            context (the retrieved chunks, joined into one string).
            Returns the generated answer text.
        """
        raise NotImplementedError