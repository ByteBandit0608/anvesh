# 6-week roadmap (~5 hrs/week)

The scaffold already gives you a working vertical slice. Each week below adds one thing that
is (a) technically real and (b) becomes a resume bullet or an interview story.

## Week 0 — today
- [ ] Create GitHub repo `anvesh`, push, confirm CI is green (Testcontainers runs on GitHub's Ubuntu runners).
- [ ] Install Docker Desktop, `docker compose up -d db`, run the app, hit Swagger at `/docs`.
- [ ] Read every file once. You must be able to explain every line — it's *your* project.

## Week 1 — Real embeddings
- [ ] Export the ONNX model (docs/EMBEDDINGS.md), run with `EMBEDDING_PROVIDER=onnx`.
- [ ] Add `OnnxEmbeddingServiceIT` that compares against known Python output (see docs).
- [ ] Ingest ~20 Wikipedia paragraphs (10 English, 10 Telugu). Search in Telugu for an English doc. It should work. Screenshot it.
- **Bullet:** *"Ran a multilingual transformer in-process via ONNX Runtime, enabling cross-lingual Telugu↔English retrieval with zero external API dependencies"*

## Week 2 — Correctness under concurrency
- [x] Make `index()` atomic with `TransactionTemplate` (delete old chunks + insert + status flip). Embedding stays outside the transaction so DB connections aren't held during CPU work.
- [x] Store the body (V2 migration) and add `POST /documents/{id}/reindex` — retries FAILED docs, re-embeds after a model change. Compare-and-set `UPDATE ... WHERE status IN (...)` prevents two concurrent reindexes.
- [x] Tests: mock-based `IngestServiceTest` asserts transaction shape (commit/rollback order); `AnveshIntegrationTest` forces `markIndexed` to throw via `@SpyBean` and asserts zero orphan chunks in real Postgres, then recovers via reindex.
- [x] `synchronized` on `embedAll` replaced by an opt-in lock (`ONNX_SERIALIZE`). **Measure it:** `scripts/bench_ingest.py` with and without; record numbers in EMBEDDINGS.md.
- [x] `POST /documents/batch` (partial success by design) + `CallerRunsPolicy` back-pressure on the ingest pool.
- [x] `ConcurrentIngestIT`: 50 docs from 8 threads + the same 50 re-submitted as a batch mid-flight → all INDEXED, contiguous ordinals, no duplicates.
- **Bullet:** *"Designed an async, idempotent ingestion pipeline (SHA-256 dedup, bounded thread pool) sustaining N docs/sec"* ← measure N.

## Week 3 — Search quality
- [x] `eval/` harness: 34 bilingual docs, 28 labelled queries, Recall@5/10 + MRR + latency per mode, per-language split, hybrid win/loss count.
- [x] `rrfK` / `candidateMultiplier` overridable per request; `run_eval.py --sweep` tabulates the effect.
- [x] Filtering: `lang=` and `filter=key:value` (JSONB `@>`), applied inside the SQL so HNSW keeps returning `limit` rows.
- [ ] **Your task:** run the eval, paste `results.md` numbers into README, and write 5 lines on the worst-scoring query.
- **Bullet:** *"Hybrid retrieval with Reciprocal Rank Fusion improved Recall@10 by X% over vector-only and Y% over BM25-only on a bilingual eval set"* ← real numbers.

## Week 4 — Production hardening
- [ ] API keys: `X-API-Key` header, keys table, Spring Security filter. Every doc gets an `owner_id` → multi-tenant search.
- [ ] Rate limiting per key with Bucket4j (in-memory first; Redis later).
- [ ] Redis cache for `search` results (key = hash of q+mode+limit+owner); measure hit rate.
- [ ] Structured JSON logs with request ids (MDC).
- **Bullet:** *"Multi-tenant API with key-based auth, per-tenant rate limiting, and Redis result caching — cut p95 latency from A ms to B ms"*

## Week 5 — Observability & deployment
- [ ] `docker-compose.yml`: add Prometheus + Grafana; build a dashboard (search p95, ingest queue depth, cache hit rate).
- [ ] Deploy: Postgres on Neon/Supabase (pgvector supported, free tier) + app on Render/Fly.io/Railway free tier. Public URL + Swagger link on the resume.
- [ ] `k6` load test script; put the numbers in README.
- **Bullet:** *"Deployed on <platform> with Prometheus/Grafana dashboards; load-tested to X RPS at Y ms p95"*

## Week 6 — Polish & the "wow"
Pick ONE:
- **(AI route)** `POST /ask` — retrieve top-k chunks and answer with a local LLM via Ollama (RAG). Cite chunk ids. Now it's a RAG system, not "just search".
- **(Systems route)** Replace the in-process executor with a Redis Streams / Kafka queue and a separate `anvesh-worker` service. Now it's a distributed system.
- **(Research route)** Per-language `tsvector` configs + a Telugu-aware tokenizer; write up findings. Ties directly into your IIIT-H work.

Then: README architecture diagram, 2-min demo GIF, blog post ("Building hybrid search on plain Postgres"). Post on LinkedIn.

---

## How it appears on the resume (target state, after Week 5)

**Anvesh — Multilingual Hybrid Search Engine** | Java 17, Spring Boot 3, PostgreSQL, pgvector, ONNX Runtime, Redis, Docker, GitHub Actions
- Built a self-hosted semantic search API combining pgvector HNSW similarity with Postgres full-text search via Reciprocal Rank Fusion, improving Recall@10 by **X%** over either retriever alone on a bilingual English/Telugu evaluation set
- Ran a multilingual sentence-transformer in-process with ONNX Runtime (mean-pooling + L2 normalisation), enabling cross-lingual Telugu↔English retrieval with zero external API dependencies
- Designed an async, idempotent ingestion pipeline (SHA-256 dedup, bounded executor, 202/poll) sustaining **N docs/s**; hardened with API-key multi-tenancy, per-tenant rate limiting, and Redis caching (p95 **A→B ms**)
- 20+ unit and Testcontainers integration tests on CI; deployed on **<platform>** with Prometheus/Grafana dashboards
