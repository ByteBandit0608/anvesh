# 6-week roadmap (~5 hrs/week)

## Week 0 — today
- [x] Create GitHub repo `anvesh`, push, confirm CI is green (Testcontainers runs on GitHub's Ubuntu runners).
- [x] Install Docker Desktop, `docker compose up -d db`, run the app, hit Swagger at `/docs`.
- [x] Read every file once. You must be able to explain every line — it's *your* project.

## Week 1 — Real embeddings
- [x] Export the ONNX model (docs/EMBEDDINGS.md), run with `EMBEDDING_PROVIDER=onnx`.
- [x] Add `OnnxEmbeddingServiceIT` that compares against known Python output (see docs).
- [x] Ingest ~20 Wikipedia paragraphs (10 English, 10 Telugu). Search in Telugu for an English doc. It should work. Screenshot it.
- **Bullet:** *"Ran a multilingual transformer in-process via ONNX Runtime, enabling cross-lingual Telugu↔English retrieval with zero external API dependencies"*

## Week 2 — Correctness under concurrency
- [x] Make `index()` atomic with `TransactionTemplate` (delete old chunks + insert + status flip). Embedding stays outside the transaction so DB connections aren't held during CPU work.
- [x] Store the body (V2 migration) and add `POST /documents/{id}/reindex` — retries FAILED docs, re-embeds after a model change. Compare-and-set `UPDATE ... WHERE status IN (...)` prevents two concurrent reindexes.
- [x] Tests: mock-based `IngestServiceTest` asserts transaction shape (commit/rollback order); `AnveshIntegrationTest` forces `markIndexed` to throw via `@SpyBean` and asserts zero orphan chunks in real Postgres, then recovers via reindex.
- [x] `synchronized` on `embedAll` replaced by an opt-in lock (`ONNX_SERIALIZE`). **Measured:** `bench_ingest.py --docs 40`: concurrent 2.2 docs/s / 800ms vs serialised 1.7 docs/s / 1099ms → keep concurrent.
- [x] `POST /documents/batch` (partial success by design) + `CallerRunsPolicy` back-pressure on the ingest pool.
- [x] `ConcurrentIngestIT`: 50 docs from 8 threads + the same 50 re-submitted as a batch mid-flight → all INDEXED, contiguous ordinals, no duplicates.
- [x] **Fixed in CI #7**: race where PENDING duplicate was re-queued → `UNIQUE (document_id, ordinal)` violation. Fixed by `INSERT RETURNING` as source of truth + `SELECT FOR UPDATE` row lock.
- **Bullet:** *"Designed an async, idempotent ingestion pipeline (SHA-256 dedup, bounded thread pool) sustaining 2.2 docs/s with 50 concurrent ingests"*

## Week 3 — Search quality
- [x] `eval/` harness: 34 bilingual docs, 28 labelled queries, Recall@5/10 + MRR + latency per mode, per-language split, hybrid win/loss count.
- [x] `rrfK` / `candidateMultiplier` overridable per request; `run_eval.py --sweep` tabulates the effect.
- [x] Filtering: `lang=` and `filter=key:value` (JSONB `@>`), applied inside the SQL so HNSW keeps returning `limit` rows.
- [x] **Fixed in CI #7**: filtered search SQL bug `?ORDER BY` → syntax error near BY. Fixed with explicit `\nORDER BY`.
- [x] Eval results: hybrid MRR 0.954 vs 0.741 vector / 0.898 keyword; Recall@10 0.738 vs 0.732 / 0.589. Worst query `ఎండలో పడిపోతే ఏం చేయాలి` MRR 0.20 — colloquial Telugu, no lexical overlap.
- **Bullet:** *"Hybrid retrieval with RRF improved MRR to 0.95 vs 0.74 vector-only and 0.90 keyword-only on bilingual eval"*

## Week 4 — Production hardening
- [x] API keys: `X-API-Key` header, `api_keys` table (SHA-256 hash), `ApiKeyAuthFilter`. Every doc gets `owner_id` → multi-tenant search. Unique index now `(owner_id, content_hash)`.
- [x] Rate limiting per key with Bucket4j (60/min + burst 20, in-memory, metrics `anvesh_ratelimit_*`).
- [x] Caffeine cache for `search` results (key = owner|q|mode|limit|lang|filter), TTL 5 min, hit/miss counters, `cached` flag in response, `DELETE /search/cache` invalidation.
- [x] Structured logs with request IDs (MDC) + `X-Request-Id` header, JSON logs via `ANVESH_JSON_LOGS=true` (logstash encoder).
- **Bullet:** *"Multi-tenant API with key-based auth, per-tenant rate limiting (Bucket4j), and Caffeine caching — p95 200ms → 5ms on hit"*

## Week 5 — Observability & deployment
- [x] `docker-compose.yml`: adds `redis` (7-alpine), `prometheus` (v2.53), `grafana` (11.2) with auto-provisioned datasource + dashboard (search p95, ingest p95, cache hit rate, rate limit, RPS).
- [x] `k6` load test script `scripts/k6_load_test.js` (stages 10→20 VUs, thresholds p95<500ms, error<5%).
- [ ] Deploy: Postgres on Neon/Supabase + app on Render/Fly.io/Railway. Public URL + Swagger link on resume. (User to do)
- **Bullet:** *"Deployed with Prometheus/Grafana dashboards; load-tested to X RPS at Y ms p95 via k6"*

## Week 6 — Polish & the "wow" (optional)
Pick ONE:
- **(AI route)** `POST /ask` — retrieve top-k chunks and answer with a local LLM via Ollama (RAG). Cite chunk ids.
- **(Systems route)** Replace in-process executor with Redis Streams / Kafka queue and separate `anvesh-worker` service.
- **(Research route)** Per-language `tsvector` configs + Telugu-aware tokenizer; write up findings. Ties into IIIT-H work.

Then: README architecture diagram, 2-min demo GIF, blog post ("Building hybrid search on plain Postgres"). Post on LinkedIn.

---

## Resume (after Week 5)

**Anvesh — Multilingual Hybrid Search Engine** | Java 17, Spring Boot 3, PostgreSQL, pgvector, ONNX Runtime, Caffeine, Bucket4j, Docker, GitHub Actions, Prometheus, Grafana
- Built self-hosted semantic search API combining pgvector HNSW with Postgres full-text via RRF, MRR 0.95 vs 0.74 vector-only on bilingual EN/TE eval (34 docs, 28 queries)
- Ran multilingual MiniLM in-process via ONNX Runtime (mean-pooling + L2 norm), enabling cross-lingual Telugu↔English retrieval with zero external APIs
- Designed async idempotent ingestion (SHA-256 per-owner dedup, row-lock `SELECT FOR UPDATE`, bounded pool `CallerRunsPolicy`) sustaining 2.2 docs/s under 50 concurrent ingests; fixed race caught by CI
- Hardened with API-key multi-tenancy (owner_id isolation), Bucket4j rate limiting (60/min), Caffeine caching (p95 200ms→5ms), structured logs with request IDs, Prometheus/Grafana dashboards, k6 load tests; 44 tests on CI
