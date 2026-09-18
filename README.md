# Anvesh (అన్వేషణ) — Multilingual Hybrid Semantic Search API

> Self-hosted search engine: POST documents in, get semantically ranked results out.
> Combines **vector similarity** (pgvector + HNSW) with **keyword full-text search** (Postgres GIN)
> using **Reciprocal Rank Fusion**. Embeddings run locally via ONNX — no API keys, works offline,
> supports 50+ languages including Telugu.

[![CI](https://github.com/<you>/anvesh/actions/workflows/ci.yml/badge.svg)](https://github.com/ByteBandit0608/anvesh/actions)

## Why this exists

Pure keyword search misses synonyms and other languages ("power outage" ≠ "electricity cut").
Pure vector search misses exact identifiers, names and rare terms. Production systems
(Elasticsearch, Vespa, Weaviate) fuse both. Anvesh is a small, readable implementation of that
idea on top of plain Postgres, built to be understood end-to-end.

## Architecture

```
            POST /api/v1/documents                GET /api/v1/search?q=...&mode=hybrid
                     │                                          │
                     ▼                                          ▼
          ┌─────────────────────┐                  ┌──────────────────────────┐
          │  DocumentController  │ 202 Accepted     │     SearchController      │
          └─────────┬───────────┘                  └────────────┬─────────────┘
                    │ submit (sha256 dedup)                      │
                    ▼                                            ▼
          ┌─────────────────────┐                  ┌──────────────────────────┐
          │    IngestService     │                  │      SearchService        │
          │  @Async ingest pool  │                  │  vector ‖ keyword (async) │
          └─────────┬───────────┘                  │  → ReciprocalRankFusion   │
                    │                              └────────────┬─────────────┘
       ┌────────────┼────────────┐                              │
       ▼            ▼            ▼                              ▼
  TextChunker  EmbeddingService  DocumentRepository     SearchRepository
  (sentence-   (ONNX MiniLM or   (JDBC, batch insert)   (<=> cosine / tsvector @@)
   aware,       hash fallback)           │                      │
   overlap)                              ▼                      ▼
                              ┌────────────────────────────────────────┐
                              │  PostgreSQL 16 + pgvector              │
                              │  documents ──< chunks(embedding, tsv)  │
                              │  HNSW index        GIN index           │
                              └────────────────────────────────────────┘
```

**Key decisions** (be ready to defend these in interviews):

| Decision | Why |
|---|---|
| Postgres + pgvector instead of a dedicated vector DB | One datastore, ACID, joins with metadata, free. Good up to low millions of vectors. |
| Plain JDBC, not JPA | pgvector operators, `tsvector`, `ON CONFLICT … RETURNING` map badly to ORMs. Explicit SQL is easier to explain and tune. |
| RRF instead of weighted score blending | Cosine ∈ [0,1] and `ts_rank` are on incomparable scales; RRF uses only ranks, no calibration needed. |
| Async ingestion with 202 + polling | Embedding is CPU-heavy (100s of ms per doc). Never block the HTTP thread on it. |
| SHA-256 content hash + unique index | Idempotent ingestion — re-posting the same doc is a no-op, not a duplicate. |
| `to_tsvector('simple')` | No English stemming → doesn't mangle Telugu. Per-language configs are a planned upgrade. |
| ONNX Runtime in-process | No Python sidecar, no network hop, no per-call cost. |
| `hash` embedding provider | Deterministic, zero-download embedder so tests/CI run in seconds. |

## Quick start

```bash
# 1. Database
docker compose up -d db

# 2. Run (hash embedder — fine for exploring the API)
./mvnw spring-boot:run   (Windows: .\mvnw.cmd spring-boot:run — see docs/SETUP_WINDOWS.md)

# 3. Try it
curl -s -X POST localhost:8080/api/v1/documents -H 'Content-Type: application/json' -d '{
  "title": "Data drift", "language": "en",
  "body": "Data drift happens when the statistical properties of input features change over time."
}'
curl -s 'localhost:8080/api/v1/search?q=why+does+model+accuracy+drop&mode=hybrid' | jq
```

Swagger UI: http://localhost:8080/docs · Metrics: http://localhost:8080/actuator/prometheus

For **real semantic search**, download the multilingual model (see [docs/EMBEDDINGS.md](docs/EMBEDDINGS.md)) and run with `EMBEDDING_PROVIDER=onnx`.

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/documents` | Submit document → `202` + id (or `200` if duplicate) |
| `GET` | `/api/v1/documents/{id}` | Status: `PENDING` / `INDEXED` / `FAILED` |
| `GET` | `/api/v1/documents` | List (paginated) |
| `DELETE` | `/api/v1/documents/{id}` | Delete document + chunks (cascade) |
| `GET` | `/api/v1/search?q=&mode=&limit=` | `mode` = `hybrid` (default) · `vector` · `keyword` |

## Testing

```bash
./mvnw verify          # unit tests + Testcontainers integration test (needs Docker)
```

- Unit: chunker edge cases (incl. Telugu danda), RRF maths, embedder determinism, ONNX mean-pooling.
- Integration: real `pgvector/pgvector:pg16` container → ingest → poll → search in all 3 modes → dedup → delete.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md) — a 6-week plan with the exact bullets each week earns you.

## Licence

MIT
