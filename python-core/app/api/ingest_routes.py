"""
ingest_routes.py
----------------
POST /ingest — accepts a file upload, runs it through the full
ingestion pipeline (convert -> chunk -> embed -> store), and
returns a summary of what was stored.
"""
import shutil
import tempfile
from pathlib import Path

from fastapi import APIRouter, UploadFile, File, HTTPException
from pydantic import BaseModel

from app.ingestion.chunker import chunk_document
from app.ingestion.converter import convert_to_markdown
from app.ingestion.embedder import embed_batch
from app.retrieval.dense_search import ensure_collection, upsert_chunks

router = APIRouter()

class IngestResponse(BaseModel):
    source_doc: str
    chunks_stored: int
    sections_found: list[str]

@router.post("/ingest", response_model=IngestResponse)
async def ingest_file(file: UploadFile = File(...)) -> IngestResponse:
    # UploadFile gives us a stream, but converter.convert_to_markdown
    # needs a real path on disk (MarkItDown's convert_local requirement).
    # Save to a temp file, always clean it up afterward.
    suffix = Path(file.filename).suffix
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        shutil.copyfileobj(file.file, tmp)
        tmp_path = Path(tmp.name)
    try:
        try:
            markdown_text = convert_to_markdown(tmp_path)
        except Exception as e:
            raise HTTPException(
                status_code=400,
                detail=f"Failed to convert file {file.filename}: {e}"
            )

        chunks = chunk_document(markdown_text, source_doc=file.filename)

        if not chunks:
            raise HTTPException(
                status_code=400,
                detail=f"No chunks were created from file {file.filename}. Is it empty or unsupported?"
            )

        vectors = embed_batch([chunk.text for chunk in chunks])

        ensure_collection()
        upsert_chunks(chunks, vectors)

        sections = list({chunk.section_title for chunk in chunks})

        return IngestResponse(
            source_doc=file.filename,
            chunks_stored=len(chunks),
            sections_found=sections
        )
    finally:
        tmp_path.unlink(missing_ok=True)  # clean up temp file


