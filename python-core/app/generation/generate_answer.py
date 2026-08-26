"""
generate_answer.py
--------------------
Picks a provider and generates an answer, with automatic fallback:
try Groq first (primary), fall back to Ollama (offline) if Groq fails.
"""


from app.generation.groq_provider import GroqProvider
from app.generation.ollama_provider import OllamaProvider

_groq = GroqProvider()
_ollama = OllamaProvider()

def generate_answer(prompt: str, context: str) -> str:
    try:
        return _groq.generate(prompt, context)
    except Exception as groq_exception:
        print(f"[generate_answer] Groq failed: {groq_exception}. Falling back to Ollama.")
        try:
            return _ollama.generate(prompt, context)
        except Exception as ollama_exception:
            print(f"[generate_answer] Ollama also failed: {ollama_exception}. No answer generated.")
            return "[ERROR] Unable to generate an answer, both generations failed."