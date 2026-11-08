# Sentinel-RAG: Engineering Handoff & Implementation Plan

Nov 7, 2026 · @Tarunpreet Singh

## 1. Status at a glance

The Python inference plane and the Java orchestration layer are built. The real evaluation engine, the live gRPC server and all GitHub reporting are still missing. This reflects the last known state on the as-of date above; check the repo (github.com/GodXSpell/SentinentRAG) before starting, since it may have moved.

Of the original 4-week plan, Week 1 (Python data plane) and Week 3 (Java orchestrator, cache, gate) are done. Week 2 (real gRPC server and evaluators) and Week 4 (CI workflow, PR comments, end-to-end demo) are not.

| Component | Status | Notes |
| --- | --- | --- |
| Ingestion: MarkItDown, section-aware chunking, embeddings into Qdrant | Built, verified | MarkItDown is used unmodified, no custom converter |
| Hybrid retrieval: Qdrant dense and BM25 sparse, Reciprocal Rank Fusion | Built, verified |  |
| Cross-encoder reranking | Built | ms-marco-MiniLM-L-6-v2 |
| Self-correction state machine | Built, verified | Thresholds 0.75 and 0.40, MAX\_RETRIES = 1 |
| Query rewriter on Groq | Built, verified | Reasoning disabled, 3 rewrites |
| Hallucination guard on spaCy | Built |  |
| FastAPI /ingest and /query | Built, verified |  |
| Python gRPC server | Stub only | Proves Java to Python connectivity, no real logic |
| Local evaluators: faithfulness, context precision, answer relevancy | Not started | Core of the CI gate |
| GitHub webhook intake and HMAC verification | Built, tested | Tested with real GitHub webhook traffic |
| Java entities and Flyway migrations V1 to V4 | Built |  |
| Evaluation cache service | Built | Not yet exercised with real evaluation results |
| Quality gate and p95 latency calculator | Built |  |
| Async dispatch and orchestration | Built | Verified through RUNNING to SKIPPED or FAILED |
| gRPC client and result mapper | Built |  |
| Golden test case loading | Not started |  |
| Pipeline config fetched from the PR | Not started |  |
| GitHub commit status and PR comment | Not started |  |
| CI workflow, hosting, Dockerfiles | Not started |  |
| Tests and benchmarks | Partial | Some Python tests exist, nothing systematic |
| README | Needs rewrite | See section 10 |

## 2. Problem and goals

Sentinel-RAG attacks two failures of production RAG systems: hallucinations caused by poor retrieved context, and regression testing that is too slow and expensive to run on every commit. It is a portfolio project, so every claim in the README must be backed by a measured number.

1. Runtime: a self-correcting retrieval loop rewrites the query and changes search mode when context quality is low, and refuses to answer when it cannot find adequate support.
2. CI/CD: a quality gate runs evaluation on pull requests, and a content-hash cache lets unchanged test cases skip the expensive LLM and evaluator work.

Targets from the original spec are below. None has been measured yet, so none may be claimed until the benchmarks in section 9 produce numbers.

| Metric | Target | How it is measured |
| --- | --- | --- |
| PR canary check wall-clock | under 15 s with a warm cache | Benchmark script, cold vs warm cache |
| First-pass retrieval latency | p95 under 600 ms | Latency on the /query path |
| Latency when a rewrite triggers | p95 under 1.2 s | Same path, retry cases only |
| Context faithfulness | at least 0.85 | Local NLI evaluator on the golden suite |
| Evaluation spend reduction | at least 80 percent | Calls or tokens with and without the cache over a commit sequence |
| Gate thresholds | faithfulness at least 0.85, context precision at least 0.80, p95 latency at most 800 ms | Quality gate service |

The 15 s target only holds with a warm model, a pre-built index for the current chunking config, and a warm cache. A first run on a new chunking config will be slower, and the README must say so.

Personas: the AI/ML engineer who wants a safety net when changing chunking, embeddings or prompts, and the enterprise consumer who needs factual answers with no hangs and no infinite loops.

## 3. Architecture

&#91;embedded content: system architecture, two planes, built vs to build\]

The Java plane owns CI orchestration and quality decisions. The Python plane owns retrieval, correction, generation and evaluation. The only interface between them is the shared proto file, and neither service reads the other's database or internal state.

## 4. Architecture decisions

These choices were made deliberately; do not reverse them without a reason. Rows marked Proposed are new decisions from the status review, open to challenge before work starts.

| Decision | Choice | Why | State |
| --- | --- | --- | --- |
| Service split | Java control plane, Python inference plane, one shared proto, no shared database | Java suits CI orchestration and gating, Python owns the ML ecosystem, and the boundary stays testable | Decided, built |
| Retry threshold | The same 0.75 bar applies after a retry | Keeps the faithfulness guarantee uniform, trading a lower refusal rate for reliability | Decided, built |
| Context score | Sigmoid over reranker output, not a clip to zero | A clip collapses all negative scores and loses the gap between mediocre and clearly irrelevant matches | Decided, built |
| Retry reranking | Rerank against the original query and all 3 rewrites, using mean score | Reranking only against the original discarded candidates that BM25 had correctly found under rewritten vocabulary | Decided, built |
| Retry behaviour | MAX\_RETRIES = 1, switch from hybrid to sparse-only search | Bounds latency and rules out loops | Decided, built |
| Reasoning models | reasoning\_effort none and reasoning\_format hidden on rewriter and generator | Reasoning models can spend the whole token budget on hidden reasoning and return an empty answer | Decided, built |
| LLM providers | Pluggable interface, Groq primary, Ollama offline fallback | Free primary API with a local fallback | Decided, built |
| Ingestion | MarkItDown used as is, no custom converter | Project requirement | Decided, built |
| Cache key | SHA-256 of each field, hashes then concatenated, over exact inputs | Avoids ambiguity between adjacent fields. This is an exact-match cache, so call it a content-hash cache and not a semantic one | Decided, built |
| JPA entities | Getter, Setter and NoArgsConstructor, not @Data, with DB-generated UUID keys | Avoids equals and hashCode and lazy-load pitfalls | Decided, built |
| Build record writes | RUNNING first, then update to a final status. SKIPPED for empty suites | The state is visible during long runs and failures never disappear | Decided, built |
| Async dispatch | A separate EvaluationDispatcher bean carries @Async | Calling @Async on the same bean bypasses the Spring proxy | Decided, built |
| Gate thresholds | Externalised through @ConfigurationProperties | Tunable without code changes | Decided, built |
| Cache key contents | Replace the retrieved chunks in the key with a corpus\_version checksum | Java computes the key before Python retrieves anything, so chunks cannot be part of it | Proposed |
| Evaluators | Local NLI and embedding models, no LLM judge | Zero API spend and a fast, deterministic CI run | Proposed |
| Pipeline config | A sentinel.yaml in the repo, read at the PR head SHA | Lets a PR change prompt or retrieval settings so a bad change can actually fail | Proposed |
| Qdrant index | One collection per chunking-config hash, reused when it exists | A chunking change only re-indexes once, protecting the 15 s budget | Proposed |
| PR sampling | Seeded canary subset on PRs, full suite on main pushes | Keeps PR runs fast. A sampled pass is weaker evidence than a full pass | Proposed |
| GitHub reporting | Commit status and one updated PR comment through a fine-grained token | The Checks API needs a GitHub App, which can come later | Proposed |
| Trigger model | Webhook-driven, the Java service reports status itself | Matches the architecture. Needs a host that stays up | Proposed |

## 5. What is already built

Read the existing code before adding to it. The notes below say where each responsibility lives and which bugs were already fixed.

### Python plane (app/)

- ingestion/: MarkItDown conversion, section-aware chunking with a token-bounded sliding window and overlap, sentence-transformer embeddings stored in Qdrant with cosine similarity.
- retrieval/: dense search, BM25 sparse search, Reciprocal Rank Fusion, cross-encoder reranking (ms-marco-MiniLM-L-6-v2), and rerank\_multi\_query for retries.
- correction/: state machine with DIRECT\_THRESHOLD 0.75, RETRY\_FLOOR 0.40 and MAX\_RETRIES 1, sigmoid context scoring, the query rewriter, and orchestration. Below the floor, or still below 0.75 after the retry, it returns the fixed refusal: Insufficient local context found to answer safely.
- correction rewriter: Groq model qwen/qwen3.6-27b with reasoning\_effort none, reasoning\_format hidden and max\_tokens 300. Confirm the model ID is still served by Groq before relying on it.
- generation/: provider interface with Groq and Ollama implementations.
- guard/: spaCy extraction of dates, numbers and named entities from the answer, stripping any claim whose entity is absent from the retrieved context.
- api/: POST /ingest and POST /query. config.py holds central settings, and a .env file holds the Groq key and the Qdrant URL.

Bugs already found and fixed, which must not come back:

- Variable shadowing in the chunker sliding-window loop overwrote the accumulator.
- A from-import of a threshold copies the value at import time, so monkeypatching it in tests silently does nothing. Reference the module attribute, or pass config explicitly.
- A reasoning model burned its entire token budget on hidden reasoning and returned nothing.

### Java plane

Stack: Spring Boot 4.1.1, Java 21, Maven, Hibernate 7, Flyway, Lombok, PostgreSQL, the Spring gRPC client starter, and spring-boot-jackson2 for Jackson 2 compatibility.

- Entities: TestSuite, TestCase, EvalCache, CiBuildRun and TestResultRecord, with UUID primary keys generated by the database. Flyway migrations V1 to V4, where V4 adds the SKIPPED build status constraint.
- Services: SemanticCacheService (the content-hash cache), QualityGateService with QualityGateThresholdsConfig, LatencyPercentileCalculator (nearest-rank p95), GitHubWebhookVerifier (HMAC-SHA256 compared with MessageDigest.isEqual), WebhookIngestionService, EvaluationOrchestrationService and EvaluationDispatcher.
- gRPC: RagEngineGrpcClient and RagEvaluationMapper. A stub Python server proved connectivity on port 50051.
- Verified end to end: HMAC check, payload parsing, async dispatch, then CiBuildRun saved as RUNNING and moved to SKIPPED or FAILED. Real evaluation results have never flowed through this path.

Spring Boot 4.x changes that already cost time:

- Hibernate 7 removed GenerationTime. Use @Generated with org.hibernate.generator.EventType.INSERT.
- flyway-core alone no longer triggers auto-configuration. Use spring-boot-starter-flyway.
- Jackson 3 is the default, so the old ObjectMapper is missing until spring-boot-jackson2 is added.
- The gRPC client property is spring.grpc.client.channel.NAME.target, singular, in Boot 4.1. The older plural channels address key is silently ignored and the client falls back to port 9090.
- javax.annotation.Generated no longer exists, which affects proto code generation.

## 6. gRPC contract

The proto file src/main/proto/rag\_service.proto is the single source of truth, and both the Java and Python stubs are generated from it. Settle the changes below first, because every later task depends on them.

Current contract: service RagEngine has QueryRAG (RAGQueryRequest to RAGQueryResponse) and RunEvaluation (EvalBatchRequest to EvalBatchResponse). A TestCase carries test\_id, query, expected\_ground\_truth, prompt\_template and cache\_hash. A TestResult carries the three scores, execution\_time\_ms, was\_cached and generated\_answer.

| Change | Why |
| --- | --- |
| Add a PipelineConfig message to EvalBatchRequest | Without it Python cannot learn what a PR changed, so a bad prompt or chunking setting could never fail the gate |
| PipelineConfig fields: prompt\_template, chunk size and overlap in tokens, retrieval top\_k, rerank top\_k, embedding model, generator provider and model, corpus\_version, config\_hash | Everything that can change retrieval or generation behaviour, and the inputs of the cache key |
| Deprecate the per-case prompt\_template, do not renumber it | Field numbers are never reused or changed |
| Add error, refused, retry\_count and self\_correction\_triggered to TestResult | Reporting needs to tell a failed case from a refusal, and the PR comment should show how often correction fired |
| Send only cache misses in EvalBatchRequest | The cache lives in Java, so Python should never see a case that Java already answered |
| Set a gRPC deadline per batch on the Java client | A hung Python service must end a build with an error and not leave it RUNNING |

Interface sketch of the new message, for the implementer to finalise:

```protobuf
message PipelineConfig {
  string prompt_template = 1;
  int32 chunk_size_tokens = 2;
  int32 chunk_overlap_tokens = 3;
  int32 retrieval_top_k = 4;
  int32 rerank_top_k = 5;
  string embedding_model = 6;
  string generator_provider = 7;
  string generator_model = 8;
  string corpus_version = 9;
  string config_hash = 10;
}
```

Add it to EvalBatchRequest as the next unused field number, and add the new TestResult fields the same way.

## 7. Data model and cache key

PostgreSQL holds golden test data, cached evaluation results and CI history. The built entities use database-generated UUID keys; the VARCHAR keys in the original spec are out of date, so follow the code and Flyway migrations.

| Entity | Holds |
| --- | --- |
| TestSuite | A named golden suite |
| TestCase | Query and expected ground truth for one case, tied to a suite |
| EvalCache | Cache hash, generated answer, the three scores, evaluator model and creation time |
| CiBuildRun | Commit SHA, branch, PR id, status (RUNNING, PASSED, FAILED, SKIPPED), mean scores, cache hit rate, duration |
| TestResultRecord | Per-case result of a build, used for the gate and for baseline comparison |

Cache key rules:

- Key = SHA-256 over prompt template, canonical config, query and corpus\_version. Do not include retrieved chunks, because Java computes the key before Python retrieves anything.
- Canonicalise the config as JSON with sorted keys and fixed number formatting, or the same config will hash differently across runs.
- corpus\_version is a checksum of the source documents plus the ingestion and chunking settings. Any change to the corpus or chunking changes every key, which is the correct invalidation.
- Include the evaluator model or version in the key or in an evaluator\_version column, so changing an evaluator invalidates old scores.
- Verify the key that is currently built matches these rules, and fix it if not, before any benchmark is run.

Baseline for the PR delta report: the most recent PASSED CiBuildRun on main. If none exists, the comment shows absolute scores with no deltas.

## 8. Work to implement

The order is fixed by dependencies: contract first, then the Python evaluation plane and the Java completion in parallel if there are two people, then CI, hosting and the demo. Estimated effort is about three weeks for one person. The implementer writes the code; each task below names the files, the interface and the finish line.

### Phase 1: contract and cache key (about 1 day)

| Task | Files and interface | Done when |
| --- | --- | --- |
| 1.1 Extend the proto | rag\_service.proto: PipelineConfig, new TestResult fields, per-batch config. Regenerate Java and Python stubs | Both services compile and the stub server returns the new fields |
| 1.2 Build the cache key | cache/CacheKeyBuilder in Java, canonical JSON, SHA-256 per section 7 | Unit tests: same inputs give the same hash, reordered config keys give the same hash, any single field change gives a different hash |
| 1.3 Decide refusal scoring | See open question in section 10 | Decision written into this doc |

### Phase 2: Python evaluation plane (about 1 week)

New package app/evaluation/ plus changes to grpc\_server/. Local models only, so evaluation costs no API tokens.

| Task | Files and interface | Done when |
| --- | --- | --- |
| 2.1 Pipeline config object | A PipelineConfig dataclass built from the proto and passed explicitly down the call chain. No module-level mutation | The same process can serve two different configs in one run without interference |
| 2.2 Index per config | An index manager with ensure\_collection(config\_hash, corpus\_version): reuse the Qdrant collection if it exists, otherwise ingest through the existing MarkItDown pipeline | A repeated config skips ingestion, and a changed chunk size creates a new collection |
| 2.3 Faithfulness | faithfulness.py: split the answer into sentences with spaCy, take the maximum NLI entailment of each sentence against each retrieved chunk using a small local NLI cross-encoder, and score the share of supported sentences | The score separates faithful from hallucinated answers on the calibration set |
| 2.4 Context precision | context\_precision.py: judge each retrieved chunk against the expected ground truth, then aggregate rank-weighted so relevant chunks ranked higher score better | Ordering relevant chunks first scores higher than ordering them last on the same set |
| 2.5 Answer relevancy | answer\_relevancy.py: cosine similarity between embeddings of the answer and the expected ground truth, reusing the existing embedding model | Scores are in 0 to 1 and ordered sensibly on the calibration set |
| 2.6 Runner | runner.py: for each case run retrieve, correct, generate and guard, then score, timing each case. Bounded concurrency with a semaphore sized to the Groq rate limit. Per-case errors are returned in the result, never raised | A 20-case batch completes with one deliberately broken case reported as an error and the rest scored |
| 2.7 Real gRPC service | grpc\_server/service.py: QueryRAG calls the existing orchestrator and RunEvaluation calls the runner. Load all models once at startup. Map exceptions to gRPC status codes | Java can call both methods against the real service, with no stub |
| 2.8 Calibration set | tests/calibration/: 10 to 20 hand-written cases covering faithful answers, hallucinated answers and irrelevant context | Thresholds chosen from measured scores, with the numbers recorded in the README |

### Phase 3: Java completion (about 1 week)

Extends the existing packages webhook/, testsuite/, cache/, qualitygate/ and github/.

| Task | Files and interface | Done when |
| --- | --- | --- |
| 3.1 Golden suite loader | testsuite/: load golden/\*.yaml into TestSuite and TestCase with an idempotent upsert, run from an admin endpoint or at startup | Loading twice creates no duplicates, and the suite is version-controlled in the repo |
| 3.2 Config fetcher | A PipelineConfigFetcher that reads sentinel.yaml at the PR head SHA through the GitHub contents API, validates it, and computes corpus\_version and config\_hash | An invalid or missing file fails the build with a clear message in the PR comment |
| 3.3 Cache read and write | cache/: look up each case with CacheKeyBuilder, and write EvalCache rows from real Python results | A second run of an unchanged commit reports a cache hit rate of 100 percent and makes no gRPC call |
| 3.4 Orchestration | EvaluationOrchestrationService: split cases into hits and misses, apply seeded canary sampling on PRs and the full suite on main pushes, send misses, merge results, record cache hit rate, evaluate the gate, persist TestResultRecord and the final CiBuildRun status | A full pass and a deliberate fail both end in the right final status |
| 3.5 Failure handling | A gRPC deadline per batch, retry only on UNAVAILABLE, and a final ERROR or FAILED status with a PR comment if Python is down | Stopping the Python service mid-run ends the build in under the deadline and never leaves it RUNNING |
| 3.6 Webhook coverage and idempotency | Handle pull\_request opened, synchronize and reopened, plus push to main. Store the X-GitHub-Delivery id and ignore duplicates. The original spec lists only opened and push, which would miss new commits on an open PR | New commits on an open PR re-run the check, and a redelivered webhook is ignored |
| 3.7 GitHub status client | github/GitHubStatusClient: pending at the start, then success or failure with a link | The status shows on the PR within seconds of the webhook |
| 3.8 PR comment | github/PrCommentService: a markdown table of scores against the main baseline, found and updated by a hidden marker so there is one comment per PR | A second push updates the same comment and does not add another |

### Phase 4: packaging, hosting and demo (2 to 3 days)

| Task | Files and interface | Done when |
| --- | --- | --- |
| 4.1 Config schema and examples | A documented sentinel.yaml schema, an example file, an example golden suite and a small corpus in the repo | A new user can copy the example and get a working run |
| 4.2 Containers | A Dockerfile for each service and a Compose file for the full stack: PostgreSQL, Qdrant, optional Ollama, Python and Java | One command starts everything on a clean machine |
| 4.3 Hosting | An always-on host with public HTTPS for the webhook and a real webhook secret. ngrok remains the development path only | A webhook from a real PR reaches the host without a manual tunnel |
| 4.4 Project CI | A GitHub Actions workflow in this repo that builds and tests both services on every push | The badge is green and fails on a broken test |
| 4.5 Demo | A demo branch with a deliberately broken prompt or chunking change, recorded next to a passing PR | The README shows both PR comments and the timing |

## 9. Testing, benchmarks and deployment

Tests run alongside each phase and not at the end. Benchmarks produce the numbers the README is allowed to claim.

| Area | What to test |
| --- | --- |
| Python state machine | Boundaries at 0.39, 0.40, 0.74 and 0.75, the retry path, MAX\_RETRIES, and the fixed refusal message |
| Python retrieval | Chunker overlap and the shadowing bug, rank fusion order, rerank\_multi\_query keeping BM25-only candidates |
| Python guard | Stripping of unsupported dates, numbers and entities |
| Python evaluators | Calibration set separation, handling of empty answers and refusals |
| Python gRPC | An in-process server exercised by a test client |
| Java cache | Key stability, canonical JSON, change sensitivity |
| Java gate | Each threshold at, just above and just below its boundary, and the p95 calculation |
| Java webhook | HMAC verification, duplicate delivery ignored, event type coverage |
| Java integration | A Testcontainers PostgreSQL test from webhook to final status, with a fake gRPC server |
| Contract | CI fails if the proto changes without regenerated stubs |

Benchmark protocol. Record the machine, model versions and sample size with every number.

1. CI wall-clock: cold cache and warm cache, with the models loaded and the index pre-built, median and p95 over 10 runs. Also report the first run after a chunking change, which will be much slower.
2. Cache hit rate over a scripted sequence of 10 commits that each change one thing.
3. Retrieval latency p95 on first-pass queries versus queries that trigger a rewrite.
4. Spend reduction: count generation and evaluator calls over the same 10-commit sequence with and without the cache, and report the percentage saved.
5. Gate sanity: a broken prompt must fail, a good one must pass, and 10 neutral commits must not be blocked.

Deployment: the Java service needs a public HTTPS endpoint, a real webhook secret and a GitHub token with commit-status and pull-request write access. Keep the Groq key, the token, the webhook secret and the database URL in environment variables, never in the repo. Size evaluation concurrency to the current Groq free-tier limits, which should be checked at build time.

Illustrative sentinel.yaml sketch, for the implementer to finalise. The values are examples, not defaults:

```yaml
suite: default
prompt_template: prompts/answer.txt
chunking:
  size_tokens: 300
  overlap_tokens: 50
retrieval:
  top_k: 20
  rerank_top_k: 5
generator:
  provider: groq
  model: <model id>
```

## 10. Risks, open questions, README fixes and environment

The project is finished when pushing a deliberately broken prompt or chunking change to a PR fails the check with a score-delta comment, within 15 s on a warm cache, and a good change passes.

### Risks

| Risk | Effect | Mitigation |
| --- | --- | --- |
| Evaluator quality is unproven | The gate blocks everything or lets everything pass | Build and tune on the calibration set first, and record the error rates |
| The 15 s target holds only when warm | A cold or re-indexing run is far slower | Pre-built index per config, warm models, and honest reporting of cold numbers |
| Groq free-tier rate limits | Parallel evaluation stalls or fails | Bounded concurrency, retries with backoff, and the Ollama fallback |
| The Groq model ID is retired | Rewriting and generation stop working | Keep the model ID in config and confirm it before each milestone |
| Sampled PR runs miss regressions | A weak PR pass | Full suite on main pushes, and the PR comment states that it was a sample |
| No always-on host | The webhook cannot reach the service | Decide hosting in Phase 4 at the latest |

### Open questions for the project owner

1. How is a refusal scored? A refusal is correct when a case expects one and a failure when the case expects an answer. This needs an explicit flag on golden cases.
2. Does the webhook watch this repo, where the Python service is the RAG under test, or a separate RAG application repo? It decides where sentinel.yaml lives.
3. Which NLI model is used? Choose after the calibration comparison, not before.
4. Is a fine-grained token enough, or is a GitHub App wanted for the Checks API?
5. What is the hosting target?

### README corrections

- Add the evaluators to the Python plane description, and mark the gRPC server and GitHub reporting as in progress until they exist.
- Rename the semantic cache to a content-hash evaluation cache.
- State what runs in Docker Compose. The README says Qdrant and PostgreSQL, while the dev setup runs Qdrant and Ollama, so check which is true.
- Make the repo layout match the run instructions: app/ versus python-core/app, and list main.py and tests/.
- Add Java run instructions, Postgres setup, environment variables (webhook secret, GitHub token, Groq key), proto generation, and ngrok for development.
- Restore the measurable goals, add a Results section filled only from section 9 benchmarks, and add a Limitations section.
- Note that the cross-encoder is not the INT8 ONNX model from the original spec.

### Environment

Development runs on Linux with Java 21, Maven, Python, Docker Compose for Qdrant and Ollama, the application services directly on the host, PostgreSQL, and ngrok for local webhook testing. Open one PR per task from section 8, describe the design reasoning in the PR, and follow the decisions in section 4 unless the PR argues for a change.
