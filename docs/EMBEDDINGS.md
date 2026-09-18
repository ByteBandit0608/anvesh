# Getting the real embedding model

The default `hash` provider is a stand-in. For real multilingual semantics, export
`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` (384-dim, ~470 MB, supports Telugu) to ONNX.

## One-time export (Python, on your laptop — the app itself never needs Python)

```bash
pip install optimum[exporters] sentence-transformers
optimum-cli export onnx \
  --model sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2 \
  --task feature-extraction \
  models/paraphrase-multilingual-MiniLM-L12-v2
```

That folder will contain `model.onnx` and `tokenizer.json` — exactly what `application.yml` points at.

## Run with it

```bash
EMBEDDING_PROVIDER=onnx ./mvnw spring-boot:run
# or
EMBEDDING_PROVIDER=onnx docker compose up --build
```

## Sanity check (Week 2)

Write a test that embeds `"How do I reset my password?"` and `"పాస్‌వర్డ్ మార్చడం ఎలా?"` and asserts
cosine similarity > 0.6, while `"Java garbage collection"` scores < 0.3. If the first pair scores
low, your mean-pooling or normalisation is wrong — compare against Python `model.encode()` output
for the same string (they should match to ~1e-4).

## Smaller / faster alternatives

| Model | Dim | Size | Notes |
|---|---|---|---|
| `paraphrase-multilingual-MiniLM-L12-v2` | 384 | 470 MB | default; good Telugu |
| `intfloat/multilingual-e5-small` | 384 | 470 MB | better quality; needs `"query: "` / `"passage: "` prefixes |
| `BAAI/bge-m3` | 1024 | 2.2 GB | SOTA multilingual; change `vector(384)` → `vector(1024)` |

Changing dimension = new Flyway migration (`V2__…`) + update `anvesh.embedding.dimension`.
