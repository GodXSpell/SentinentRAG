# Sentinel-RAG

Sentinel-RAG is a dual-plane reliability platform for Retrieval-Augmented Generation (RAG) systems. It combines a self-correcting retrieval and generation runtime with a CI/CD quality gate, aimed at solving two problems that commonly affect production RAG applications: silent hallucinations caused by poor retrieval context, and slow, expensive regression testing for changes to prompts, chunking, or retrieval configuration.

The system is split into two cooperating services:

- A Python service that handles document ingestion, hybrid retrieval, reranking, self-correction, generation, and hallucination checking.
- A Java service that handles CI/CD orchestration, GitHub webhook integration, semantic caching of evaluation results, and quality gate decisions.

The two services communicate over gRPC using a shared Protobuf contract.

## Problem Statement

RAG systems fail in two characteristic ways. At runtime, retrieval can surface irrelevant, outdated, or conflicting context, which leads generation models to hallucinate rather than admit uncertainty. Separately, teams iterating on RAG pipelines lack a fast, cheap way to verify that a change to chunking, embeddings, or prompts has not regressed retrieval quality, since full evaluation runs against an LLM judge are slow and expensive to run on every commit.

Sentinel-RAG addresses the first problem with a self-correcting retrieval loop that mutates its own query and search strategy when context quality is low, and refuses to answer when it cannot find adequate support. It addresses the second problem with a semantic caching layer that lets repeated or unchanged test cases skip expensive LLM evaluation entirely.

## Architecture

```
GitHub --push/PR webhook--> Java Control Plane --gRPC--> Python Inference Plane
                                   |                              |
                                PostgreSQL                    Qdrant + BM25
                          (cache, test suites,              (hybrid retrieval,
                           build history)                    reranking, correction)
```

### Python Inference and Evaluation Plane

Responsible for document ingestion, retrieval, self-correction, and generation.

- Document ingestion via MarkItDown, converting arbitrary file formats into Markdown for chunking.
- Section-aware chunking with a token-bounded sliding window and overlap between adjacent chunks.
- Hybrid retrieval combining dense vector search (Qdrant, cosine similarity over sentence-transformer embeddings) and sparse keyword search (BM25), fused with Reciprocal Rank Fusion.
- Cross-encoder reranking of fused candidates to produce a final top-k context set.
- A deterministic context quality score derived from reranker output via a sigmoid transform.
- A self-correction state machine that decides, based on context quality score and retry count, whether to answer directly, rewrite the query and retry with an alternate search strategy, or refuse to answer.
- Query rewriting via an LLM, used only on retry, producing multiple alternative phrasings that are merged and reranked against all phrasings to avoid discarding relevant results found only under rewritten vocabulary.
- Answer generation through a pluggable provider interface, with a cloud provider (Groq) as primary and a local provider (Ollama) as an offline fallback.
- A local hallucination guard that extracts entities (dates, numbers, named entities) from generated answers via spaCy and strips any that do not appear in the retrieved context.
- A gRPC server exposing real-time query handling and batch evaluation to the Java control plane.

### Java Control Plane

Responsible for CI/CD orchestration and quality gating.

- A webhook controller that ingests GitHub pull request and push events.
- A test suite manager that stores golden test cases (query, expected ground truth) in PostgreSQL.
- A semantic cache that hashes prompt template, configuration, query, and context together, and returns cached evaluation results on a hit without invoking the Python evaluation service.
- A quality gate engine that evaluates a batch of test results against configurable thresholds for faithfulness, context precision, and latency, and produces a pass or fail decision.
- A GitHub status and PR comment service that reports gate results back to the pull request.

### Cross-Language Contract

The two services share a single Protobuf definition (`rag_service.proto`), from which both the Java and Python stubs are generated. This is the only interface between the two planes; neither service reaches into the other's internal state or database directly.

## Self-Correction Logic

Retrieval quality is scored on a 0 to 1 scale. Three bands determine behavior:

- Score at or above the direct threshold: proceed straight to generation.
- Score between the retry floor and the direct threshold: rewrite the query into alternate phrasings, switch from hybrid to sparse-only search, and retry once.
- Score below the retry floor, or still below the direct threshold after the single retry: refuse to answer with a fixed, deterministic message rather than generating from weak context.

The same threshold applies whether or not a retry has occurred; a retry does not receive a relaxed bar. This is a deliberate choice to keep the faithfulness guarantee uniform rather than trading reliability for a lower refusal rate.

## Repository Structure

```
app/
  ingestion/       document conversion, chunking, embedding
  retrieval/       dense search, sparse search, hybrid fusion, reranking
  correction/      state machine, scoring, query rewriting, orchestration
  generation/      provider interface, Groq and Ollama implementations
  guard/           hallucination detection and stripping
  api/             FastAPI route definitions
  grpc_server/     gRPC service implementation
  config.py        centralized settings

java control plane (separate module)
  webhook/         GitHub webhook intake
  testsuite/       golden test case persistence
  cache/           semantic cache hashing and lookup
  qualitygate/     threshold-based pass/fail decision engine
  github/          status checks and PR comments

data/
  corpus/          source documents used for local ingestion and testing

scripts/
  fetch_corpus.py  utility for pulling a sample document set
```

## Running Locally

Dependencies (Qdrant, PostgreSQL) run via Docker Compose. The application services run directly on the host during development rather than in containers.

```
docker compose up -d
```

Python service:

```
cd python-core
pip install -r requirements.txt
python -m spacy download en_core_web_sm
uvicorn app.main:app --reload
```

Environment variables are read from a `.env` file in the Python service directory, including the Groq API key and Qdrant connection URL.

## Design Notes

A few decisions in this codebase are deliberate and worth calling out for anyone reading the code:

- Reranking during retry is performed against the original query and all rewritten variants, scored by mean relevance across all of them, rather than against the original query alone. Reranking only against the original query was found to discard candidates that a rewritten query had correctly surfaced via BM25, because the original query's vocabulary did not match those candidates well even though the candidates were relevant.
- Context quality scoring uses a sigmoid rather than a ReLU-style clip. A clip collapses all negative reranker scores to the same value, discarding the difference between a mediocre match and a clearly irrelevant one. A sigmoid preserves that distinction while still bounding the score to a fixed range.
- The query rewriter and answer generator explicitly disable extended reasoning on models that support it. Reasoning-capable models can consume their entire output token budget on internal reasoning before producing a visible answer, which silently degrades a well-formed request into an empty response.