# Embedding models

Anvesh ships two `EmbeddingService` implementations, selected by `EMBEDDING_PROVIDER`:

| Provider | What it is | Use for |
|---|---|---|
| `hash` (default) | Feature-hashing of words + char trigrams. Deterministic, zero downloads, **not semantic**. | Unit tests, CI, poking at the API |
| `onnx` | `paraphrase-multilingual-MiniLM-L12-v2` running in-process on ONNX Runtime. 384-dim, 50+ languages incl. Telugu. | Real search |

## Get the model (no Python needed)

Hugging Face publishes pre-exported ONNX files for this model, so we just download them.
We use the **int8-quantised** variant (`model_quint8_avx2.onnx`, 118 MB) — ~4× smaller and
~2× faster on CPU than the fp32 file (470 MB), with negligible quality loss for retrieval.

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File scripts\download_model.ps1
```
```bash
# Linux / macOS
bash scripts/download_model.sh
```

The `models/` folder is git-ignored. The files land where `application.yml` already points.

## Switch to real embeddings

**Vectors from different models are incompatible** — anything indexed with `hash` must be
deleted before re-ingesting with `onnx`:

```powershell
python scripts\reset_index.py          # while the app is running with the OLD provider
# stop the app (Ctrl+C), then:
$env:EMBEDDING_PROVIDER = "onnx"
.\mvnw.cmd spring-boot:run
python scripts\load_samples.py         # re-ingest, now with real embeddings
```

Startup log should show `Loaded ONNX embedding model ... (inputs=[input_ids, attention_mask, token_type_ids])`
instead of the `Using HASH embedding provider` warning.

## How inference works (`OnnxEmbeddingService`)

1. **Tokenize** with the HuggingFace tokenizer (SentencePiece/Unigram for this model), batch-padded.
2. **Run** the transformer → `last_hidden_state` of shape `[batch, seq, 384]`.
3. **Mean-pool** over the sequence axis, *ignoring padding positions via the attention mask*.
4. **L2-normalise** so cosine similarity == dot product (and pgvector's `<=>` behaves).

Steps 3–4 are exactly what `sentence-transformers` does in Python. Get either wrong and
you get numbers that *look* like embeddings but rank garbage — hence `OnnxEmbeddingServiceIT`.

## Verifying

`OnnxEmbeddingServiceIT` runs automatically when the model files exist locally (skipped otherwise):

```
.\mvnw.cmd test -Dtest=OnnxEmbeddingServiceIT
```

Reference numbers with the quantised model:

| Pair | Cosine |
|---|---|
| "summer power consumption" ↔ electricity-demand sentence (EN) | ~0.71 |
| same query ↔ REST pagination sentence | ~ -0.01 |
| Telugu "వేసవిలో విద్యుత్ డిమాండ్ పెరుగుతుంది" ↔ "Electricity demand rises in summer." | ~0.69 |
| same Telugu ↔ "How do I reset my password?" | ~ -0.15 |

## Known limits / follow-ups

- Model was trained with `max_seq_length=128`; we allow 256 tokens. Telugu tokenizes into many
  more pieces than English, so an 800-char Telugu chunk may hit the limit and be truncated.
  **Week-1 task:** log `token_count` per chunk and tune `anvesh.chunking.max-chars`.
- `embedAll` is `synchronized`. Fine for one ingest thread, wasteful for four. **Week-2 task.**

## Alternatives

| Model | Dim | Notes |
|---|---|---|
| `intfloat/multilingual-e5-small` | 384 | Better quality; needs `"query: "` / `"passage: "` prefixes |
| `BAAI/bge-m3` | 1024 | SOTA multilingual; needs `vector(1024)` → new Flyway migration |
