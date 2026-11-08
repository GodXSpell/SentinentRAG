"""
main.py
-------
FastAPI application entrypoint. Run with:
    uvicorn app.main:app --reload
"""

from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.ingest_routes import router as ingest_router
from app.api.query_routes import router as query_router
from app.retrieval.dense_search import ensure_collection


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """
    Application lifespan context manager replacing deprecated @app.on_event handlers.
    Ensures Qdrant collection is ready before accepting requests.
    """
    ensure_collection()
    yield


app = FastAPI(title="Sentinel-RAG Inference Engine", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


app.include_router(ingest_router)
app.include_router(query_router)
