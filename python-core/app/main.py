"""
main.py
-------
FastAPI application entrypoint. Run with:
    uvicorn app.main:app --reload
"""

from fastapi import FastAPI

from app.api.ingest_routes import router as ingest_router
from app.retrieval.dense_search import ensure_collection

app = FastAPI(title="Sentinel-RAG Inference Engine")


@app.on_event("startup")
def on_startup() -> None:
    # Make sure the Qdrant collection exists before the app starts
    # accepting requests, so the very first /ingest call doesn't
    # have to pay for collection creation (or, worse, race against it).
    ensure_collection()


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


app.include_router(ingest_router)