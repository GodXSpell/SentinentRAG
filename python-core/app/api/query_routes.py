"""
query_routes.py
-----------------
POST /query - the real-time production RAG endpoint
(FR-2.4 self-correction, tied together with generation and the hallucination guard).
"""

from fastapi import APIRouter
from pydantic import BaseModel

from app.correction.orchestrator import run_self_correction
from app.generation.generate_answer import generate_answer
from app.guard.hallucination_guard import check_answer

router = APIRouter()

REFUSAL_MESSAGE = "Insufficient local context found to answer safely."

class QueryRequest(BaseModel):
    query: str

class QueryResponse(BaseModel):
    answer: str
    refused: bool
    self_correction_triggered: bool
    retry_count: int
    context_quality_score: float
    flagged_entities: list[tuple[str, str]] = []

@router.post("/query", response_model=QueryResponse)
async def query(request: QueryRequest) -> QueryResponse:
    result = run_self_correction(request.query)

    if result.refused:
        return QueryResponse(
            answer=REFUSAL_MESSAGE,
            refused=True,
            self_correction_triggered=result.self_correction_triggered,
            retry_count=result.retry_count,
            context_quality_score=result.context_quality_score,
        )

    context = "\n\n".join(chunk["text"] for chunk in result.chunks)
    raw_answer = generate_answer(request.query, context)
    guard_result = check_answer(raw_answer, context)

    return QueryResponse(
        answer=guard_result["clean_answer"],
        refused=False,
        self_correction_triggered=result.self_correction_triggered,
        retry_count=result.retry_count,
        context_quality_score=result.context_quality_score,
        flagged_entities=guard_result["flagged_entities"],
    )