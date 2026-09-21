# Evaluation harness

Turns "it works" into numbers.

```
python eval/run_eval.py --sweep        # app must be running with EMBEDDING_PROVIDER=onnx
```

- `corpus/eval_docs.json` — 34 documents, 6 topics (ml, energy, web, db, agriculture, health), 21 English + 13 Telugu.
  Most topics have a parallel English and Telugu document so cross-lingual retrieval can be measured.
- `queries.jsonl` — 28 queries with relevance labels (document titles). 16 English natural-language,
  8 Telugu natural-language, 4 exact-term queries (names like "HikariCP") where keyword search should win.
- `run_eval.py` — ingests the corpus (idempotent), runs every query in all three modes, computes
  **Recall@5 / Recall@10 / MRR** at document level, p50/p95 latency, per-language breakdown,
  hybrid-vs-best-single win/loss count, and (with `--sweep`) an RRF-k and candidate-multiplier sweep.
  Writes `results.md`.
- `corpus/sample_docs.json` — the original 5-doc smoke-test set used by `scripts/load_samples.py`.

## Reading the results

- **Hybrid should be ≥ the better single retriever on Recall@10** — that's the whole thesis of RRF.
  If it isn't, look at the per-query table: usually one retriever returned junk that outranked the
  other's good hit. Fixing that is a design discussion, not a bug.
- **Telugu queries will score lower than English** — the multilingual model's spaces are only
  approximately aligned (docs/EMBEDDINGS.md, "Same-language bias"). Report it honestly; it's a
  finding, and it's what your IIIT-H work is about.
- **Exact-term queries** ("HikariCP") are where keyword search rescues vector search. If keyword
  scores 1.0 and hybrid scores less than 1.0 on those, `rrfK` is too low (vector's wrong answers
  are outvoting keyword's right one) — check the sweep.
- **Latency:** vector includes one ONNX forward pass for the query (~50–100 ms on a laptop CPU);
  keyword is pure SQL. Hybrid runs both in parallel so it should be ~max, not sum.

Labels are judgement calls. If a query's failure looks like a labelling mistake, fix the label,
note it in the commit message, and rerun — that's normal in IR evaluation.
Vector search is essentially blind on this query. Its top-5 are all cosine ≈ 0.26–0.35 — noise. "HNSW" and "IVFFlat" are jargon the model tokenises into meaningless subwords; the only word it understands is recall, so it ranks the precision/recall doc (8) above the actual HNSW doc (10).
Keyword search nails it — 0.30 vs 0.20 is a 50% margin, and "hnsw" is a rare, exact token.
RRF throws the margin away. Rank 1 vs rank 2 in keyword differ by only 0.00026. Rank 8 vs rank 10 in vector differ by 0.00037. So a confident correct keyword ranking is outvoted by a noisy wrong vector ranking, because RRF only sees positions, never scores.