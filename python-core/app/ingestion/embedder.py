"""
embedder.py
-----------
Wraps a local sentence-transformers model to turn chunk text into
dense vectors for Qdrant. Model is loaded once at import time and
reused across requests (loading it per-request would be slow).
"""

from sentence_transformers import SentenceTransformer
from app.config import settings

_model: SentenceTransformer | None = None

def get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer(settings.embedding_model_name)
    return _model

def embed_text(text: str) -> list[float]:
    """Embed a single string. Returns a plain Python list of floats
        (Qdrant's client expects list[float], not a numpy array)."""
    model = get_model()
    vector = model.encode(text, normalize_embeddings=True)
    return vector.tolist()

def embed_batch(texts: list[str]) -> list[list[float]]:
    """Embed many strings at once — much faster than calling embed_text
        in a loop, since sentence-transformers batches internally on GPU/CPU."""
    if not texts:
        return []
    model = get_model()
    vectors = model.encode(texts, normalize_embeddings=True, batch_size=32)
    return vectors.tolist()