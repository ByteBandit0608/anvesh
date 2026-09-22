# Anvesh (అన్వేషణ) — Multilingual Hybrid Semantic Search API

> Self-hosted search engine: POST documents in, get semantically ranked results out.
> Combines **vector similarity** (pgvector HNSW) with **keyword full-text search** (Postgres GIN)
> via **Reciprocal Rank Fusion**. Embeddings run locally via ONNX — no API keys, works offline,
> 50+ languages including Telugu. Production-hardened with API keys, rate limiting, caching,
> Prometheus/Grafana.

[![CI](https://github.com/ByteBandit0608/anvesh/actions/workflows/ci.yml/badge.svg)](https://github.com/ByteBandit0608/anvesh/actions)

**Live demo (local Docker):** `docker compose up -d` → Swagger at http://localhost:8080/docs → Prometheus http://localhost:9090 → Grafana http://localhost:3000 (admin/anvesh)

## What it does — measured numbers

- **Hybrid retrieval:** On 34 bilingual docs (EN/TE) + 28 labelled queries:
  - Vector: R@5 0.521 R@10 0.732 MRR 0.741 p50 284ms p95 612ms
  - Keyword: R@5 0.571 R@10 0.589 MRR 0.898 p50 101ms p95 143ms
  - **Hybrid: R@5 0.643 R@10 0.738 MRR 0.954 p50 194ms p95 340ms** — MRR 0.95 vs 0.74 vector-only
  - EN split: hybrid MRR 0.975; TE split: hybrid MRR 0.900 (keyword alone drops to 0.812)
- **Ingestion:** `bench_ingest.py --docs 40` (2 cores, Neon remote):
  - `ONNX_SERIALIZE=false`: 3.4 docs/s submit, 2.2 docs/s e2e, mean index() 800ms
  - `ONNX_SERIALIZE=true`: 1.7 docs/s e2e, 1099ms → keep concurrent (25% faster, lock never needed for correctness)
- **Cache:** First search 52ms `cached:false`, second 0ms `cached:true`, 94.9% hit rate after 20 requests (Grafana)
- **Rate limiting:** Bucket4j 60/min + burst 20 → 429 + Retry-After, metrics `anvesh_ratelimit_allowed/denied`
- **Observability:** Prometheus target `anvesh (1/1 up)` scraping `/actuator/prometheus` @ 5.089ms, Grafana dashboard auto-provisioned

## Architecture

```
            POST /api/v1/documents                GET /api/v1/search?q=...&mode=hybrid
                     │                                          │
                     ▼                                          ▼
          ┌─────────────────────┐                  ┌──────────────────────────┐
          │  DocumentController  │ 202 Accepted     │     SearchController      │
          │  owner_id from       │                  │  owner-scoped + cached    │
          │  RequestContext      │                  │  cached flag in response  │
          └─────────┬───────────┘                  └────────────┬─────────────┘
                    │ submit (sha256 per-owner)                  │
                    ▼                                            ▼
          ┌─────────────────────┐                  ┌──────────────────────────┐
          │    IngestService     │                  │      SearchService        │
          │  @Async ingest pool  │                  │  vector ‖ keyword (async) │
          │  cache invalidation  │                  │  → RRF → cache put        │
          └─────────┬───────────┘                  └────────────┬─────────────┘
       ┌────────────┼────────────┐                              │
       ▼            ▼            ▼                              ▼
  TextChunker  EmbeddingService  DocumentRepository     SearchRepository
  (sentence-   (ONNX MiniLM or   (JDBC, owner_id,       (<=>
   aware,       hash fallback)    (owner_id,hash) UQ)   cosine / tsvector @@)
   overlap)          │                      │                      │
                     ▼                      ▼                      ▼
          ┌────────────────────────────────────────────────────────┐
          │  PostgreSQL 16 + pgvector + pgcrypto                   │
          │  documents (owner_id) ──< chunks(embedding, tsv)       │
          │  api_keys (key_hash SHA-256)                           │
          │  HNSW (m=16)  GIN (metadata, tsv)  (owner_id,hash) UQ  │
          └────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
                Prometheus      Grafana        Redis (7-alpine)
                :9090           :3000          :6379
```

**Key decisions — interview ready:**

| Decision | Why | What broke & how CI caught it |
|---|---|---|
| Postgres + pgvector | One datastore, ACID, joins, free. Good to low millions. | — |
| Plain JDBC, not JPA | pgvector `<=>`, `tsvector`, `ON CONFLICT RETURNING` map badly to ORMs. | — |
| RRF | Cosine [0,1] and `ts_rank` incomparable; RRF uses ranks only. | HNSW loss analysis: vector top-5 cosine 0.26-0.35 noise, keyword rank 1 vs 2 diff 0.00026 vs vector 8 vs 10 diff 0.00037 → RRF discards margin. Mitigation: weighted RRF, cosine floor 0.4. |
| Async 202/poll | Embedding CPU-heavy (100s ms). Never block HTTP. | — |
| SHA-256 per-owner + `(owner_id, content_hash)` UQ | Multi-tenant dedup, idempotent. | Race: PENDING duplicate has 0 chunks → isNew() re-queued → two indexers collide on `UNIQUE (document_id, ordinal)` under READ COMMITTED. Fix: `INSERT RETURNING` as source of truth + `SELECT FOR UPDATE` row-lock. Caught by `ConcurrentIngestIT` 50 docs/8 threads in CI. |
| `to_tsvector('simple')` | No English stemming → doesn't mangle Telugu. | — |
| ONNX in-process + quantised `model_quint8_avx2.onnx` 118MB | No Python sidecar, 4x smaller, 2x faster than fp32 470MB. | OOM on laptop with fp32 (96MB buffer) → prefer quantised in IT. |
| `filterSql` with explicit `\nORDER BY` | Text-block concat is brittle. | Bug: ` AND lang=?ORDER BY` → syntax error near BY when filter present. Only triggered with `lang`/`filter`. Fixed, caught by `AnveshIntegrationTest` in CI. |
| API keys SHA-256 hash | Never store raw; raw shown once. | — |
| Bucket4j in-memory | Zero infra, per-owner bucket 60/min burst 20. | CI red: `ConcurrentIngestIT` 50 concurrent > burst 20 → 429. Fix: `anvesh.rate-limit.enabled=false` in test profile. |
| Caffeine cache | Key `owner|q|mode|limit|lang|filter`, TTL 5m, 1000 max, hit/miss counters, `cached` flag. | — |
| RequestId + MDC | `RequestIdFilter` puts `requestId` into MDC, `X-Request-Id` header. | — |

## Quick start

```bash
# Full stack (db + redis + prometheus + grafana + app)
docker compose up -d --build
docker compose logs -f app   # wait for Started AnveshApplication

# Or infra only + run app via Maven (lighter, uses Neon if you set DB_URL)
docker compose up -d db redis prometheus grafana
./mvnw spring-boot:run
```

Swagger: http://localhost:8080/docs
Metrics: http://localhost:8080/actuator/prometheus
Prometheus: http://localhost:9090 (Targets → anvesh UP)
Grafana: http://localhost:3000 (admin/anvesh) → Dashboards → Anvesh

### With API keys (multi-tenancy)

```bash
curl -X POST http://localhost:8080/api/v1/keys -H "Content-Type: application/json" -d '{"name":"laptop"}'
# → {"rawKey":"anv_...","ownerId":"laptop-abc123",...} SAVE rawKey

curl -H "X-API-Key: anv_..." -X POST http://localhost:8080/api/v1/documents -H "Content-Type: application/json" -d '{"title":"drift","body":"Data drift happens..."}'
curl -H "X-API-Key: anv_..." "http://localhost:8080/api/v1/search?q=drift&mode=hybrid"
# second time → {"cached":true,"tookMs":0}
```

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/keys` | Create API key → 201 + rawKey (shown once) |
| `GET` | `/api/v1/keys` | List keys (hashes hidden) |
| `DELETE` | `/api/v1/keys/{id}` | Deactivate key |
| `POST` | `/api/v1/documents` | Submit → 202 + id (200 if duplicate per owner) |
| `GET` | `/api/v1/documents/{id}` | Status PENDING/INDEXED/FAILED (owner-scoped) |
| `GET` | `/api/v1/documents` | List by owner (paginated) |
| `POST` | `/api/v1/documents/{id}/reindex` | Re-chunk → 202; 409 if PENDING |
| `DELETE` | `/api/v1/documents/{id}` | Delete by owner |
| `POST` | `/api/v1/documents/batch` | Up to 100 docs, partial success |
| `GET` | `/api/v1/search?q=&mode=&limit=&lang=&filter=` | mode hybrid/vector/keyword, filters `lang=te`, `filter=topic:ml`, overrides `rrfK`, `candidateMultiplier`, returns `cached` flag |
| `DELETE` | `/api/v1/search/cache` | Invalidate cache for owner |

Headers:
- `X-API-Key: anv_...` → sets owner
- `X-Request-Id` → returned on all responses, in logs via MDC

## Evaluation

`python eval/run_eval.py --sweep` runs 28 labelled English/Telugu queries against a 34-document
bilingual corpus in all three modes and reports Recall@5/10, MRR and latency — see `eval/README.md`
and the generated `eval/results.md`.

- Local Docker stack: 5 containers (app, pgvector, redis, prometheus, grafana) all healthy
- Search cache: p95 52ms → 0ms on hit, 94.9% hit rate after 20 requests
- Prometheus scraping anvesh @ 5ms, Grafana dashboard provisioned
- API keys multi-tenancy with owner_id isolation, Bucket4j rate limiting (60/min)

```bash
python eval/run_eval.py --sweep  # 28 queries, 34 docs, all 3 modes, per-language split, worst queries(Docs: final README with measured numbers (cache 94.9%, Prometheus UP, 3 CI fixes))

## Testing

```bash
./mvnw verify   # 44 tests (9 skipped without Docker) — unit + Testcontainers (pgvector:pg16)
./mvnw test -Dtest=!OnnxEmbeddingServiceIT   # skip ONNX OOM on low-RAM laptops
```

- Unit: chunker (Telugu danda), RRF maths, embedder determinism, filter SQL injection (`te'; DROP...` stays in args), toOrQuery keeps Telugu combining marks.
- Integration: real Postgres → ingest → poll → search all modes + filters → dedup → delete; 50 concurrent ingests → all INDEXED, contiguous ordinals, no duplicates; rate-limit disabled in test profile.

## Observability

- `docker-compose.yml`: app, db, redis, prometheus (scrapes `/actuator/prometheus` @ 5s), grafana (admin/anvesh)
- `observability/prometheus.yml`, `observability/grafana/datasources/datasource.yml`, `dashboards-json/anvesh.json`
- Metrics: `anvesh_search_seconds_bucket`, `anvesh_ingest_index_seconds_bucket`, `anvesh_cache_hits/misses`, `anvesh_ratelimit_allowed/denied`, `http_server_requests_seconds`
- `scripts/k6_load_test.js`: `k6 run --vus 20 --duration 60s scripts/k6_load_test.js` → RPS, p50, p95, error rate

## Licence

MIT
