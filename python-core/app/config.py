"""
config.py
---------
Centralized settings, loaded from environment variables (.env file).
Every other module should import `settings` from here rather than
reading os.environ directly.
"""
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parents[2]

class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=PROJECT_ROOT / ".env",
        extra="ignore",
    )

    # --- Qdrant ---
    qdrant_url: str = "http://localhost:6333"
    qdrant_collection: str = "sentinel_chunks"

    # --- Embedding model ---
    embedding_model_name: str = "sentence-transformers/all-MiniLM-L6-v2"
    embedding_dim: int = 384  # must match the model above

    # --- Chunking ---
    chunk_max_tokens: int = 400
    chunk_overlap_tokens: int = 50

    # --- LLM providers (Week 1 Day 5) ---
    groq_api_key: str = ""
    ollama_base_url: str = "http://localhost:11434"

    # --- Retrieval ---
    dense_top_k: int = 20
    sparse_top_k: int = 20
    rerank_top_k: int = 5


settings = Settings()