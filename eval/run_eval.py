"""Anvesh evaluation harness (Week 3).

Ingests eval/corpus/eval_docs.json (idempotent — duplicates are skipped by SHA-256),
runs every query in eval/queries.jsonl in vector / keyword / hybrid mode, and reports
Recall@5, Recall@10, MRR and latency per mode, plus per-language breakdowns and an
RRF-k / candidate-multiplier sweep. Writes eval/results.md.

  python eval/run_eval.py                 # full run
  python eval/run_eval.py --no-ingest     # corpus already loaded
  python eval/run_eval.py --sweep         # also sweep rrfK and candidateMultiplier

Requires the app running with EMBEDDING_PROVIDER=onnx (with the hash embedder the vector
numbers are meaningless — the script warns if it detects that).

Definitions (document level — a hit counts if *any* chunk of a relevant document appears):
  Recall@k = |relevant docs in top-k| / |relevant docs|,   averaged over queries
  MRR      = 1 / rank of the first relevant doc (0 if none in top-10), averaged over queries
"""
import argparse, json, pathlib, statistics, sys, time, urllib.parse, urllib.request

BASE = "http://localhost:8080/api/v1"
ROOT = pathlib.Path(__file__).resolve().parent
CORPUS = ROOT / "corpus" / "eval_docs.json"
QUERIES = ROOT / "queries.jsonl"
OUT = ROOT / "results.md"
MODES = ["vector", "keyword", "hybrid"]
K = 10


def http(method, path, body=None, params=None):
    url = BASE + path + (("?" + urllib.parse.urlencode(params, doseq=True)) if params else "")
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def ingest(docs):
    res = http("POST", "/documents/batch", {"documents": docs})
    print(f"ingest: {res['accepted']} new, {res['duplicates']} duplicates")
    ids = [r["id"] for r in res["results"]]
    deadline = time.time() + 300
    while time.time() < deadline:
        statuses = [http("GET", f"/documents/{i}")["status"] for i in ids]
        if all(s == "INDEXED" for s in statuses):
            return
        if any(s == "FAILED" for s in statuses):
            sys.exit("some documents FAILED to index; check app logs")
        time.sleep(0.5)
    sys.exit("timed out waiting for indexing")


def doc_ranking(hits):
    """Collapse chunk hits to an ordered list of distinct document titles (first occurrence wins)."""
    seen, out = set(), []
    for h in hits:
        if h["title"] not in seen:
            seen.add(h["title"])
            out.append(h["title"])
    return out


def score(ranked, relevant):
    rel = set(relevant)
    r5 = len(rel & set(ranked[:5])) / len(rel)
    r10 = len(rel & set(ranked[:10])) / len(rel)
    rr = 0.0
    for i, t in enumerate(ranked[:10]):
        if t in rel:
            rr = 1.0 / (i + 1)
            break
    return r5, r10, rr


def run(queries, mode, extra=None, limit=None):
    """Returns per-query rows: (query, lang, r5, r10, rr, tookMs, top1)."""
    rows = []
    for q in queries:
        params = {"q": q["query"], "mode": mode, "limit": limit or 25}   # 25 chunk hits ≈ ≥10 distinct docs
        if extra:
            params.update(extra)
        res = http("GET", "/search", params=params)
        ranked = doc_ranking(res["hits"])
        r5, r10, rr = score(ranked, q["relevant"])
        rows.append((q["query"], q["lang"], r5, r10, rr, res["tookMs"], ranked[0] if ranked else "-"))
    return rows


def summarise(rows):
    n = len(rows)
    if n == 0:
        return None
    took = sorted(r[5] for r in rows)
    p95 = took[min(n - 1, int(round(0.95 * (n - 1))))]
    return {
        "n": n,
        "R@5": sum(r[2] for r in rows) / n,
        "R@10": sum(r[3] for r in rows) / n,
        "MRR": sum(r[4] for r in rows) / n,
        "p50ms": statistics.median(took),
        "p95ms": p95,
    }


def fmt_table(title, results, key_name="mode"):
    lines = [f"### {title}", "", f"| {key_name} | n | Recall@5 | Recall@10 | MRR | p50 ms | p95 ms |", "|---|---|---|---|---|---|---|"]
    for k, s in results.items():
        if s:
            lines.append(f"| {k} | {s['n']} | {s['R@5']:.3f} | {s['R@10']:.3f} | {s['MRR']:.3f} | {s['p50ms']:.0f} | {s['p95ms']:.0f} |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-ingest", action="store_true")
    ap.add_argument("--sweep", action="store_true")
    args = ap.parse_args()

    docs = json.loads(CORPUS.read_text(encoding="utf-8"))
    queries = [json.loads(l) for l in QUERIES.read_text(encoding="utf-8").splitlines() if l.strip()]
    print(f"{len(docs)} docs, {len(queries)} queries")

    if not args.no_ingest:
        ingest(docs)

    # Sanity: with the hash embedder, vector search is random. Detect via a trivial paraphrase probe.
    probe = doc_ranking(http("GET", "/search", params={"q": "electricity demand in summer", "mode": "vector", "limit": 5})["hits"])
    if probe and "electricity" not in probe[0].lower() and "విద్యుత్" not in probe[0]:
        print("WARNING: vector search looks non-semantic — is the app running with EMBEDDING_PROVIDER=onnx?")

    report = ["# Anvesh evaluation results", "", f"_Corpus: {len(docs)} docs ({sum(d['language']=='en' for d in docs)} en, "
              f"{sum(d['language']=='te' for d in docs)} te), {len(queries)} labelled queries. Generated by eval/run_eval.py._", ""]

    # 1. Main comparison
    per_mode_rows = {m: run(queries, m) for m in MODES}
    overall = {m: summarise(rows) for m, rows in per_mode_rows.items()}
    report.append(fmt_table("Overall", overall))

    # 2. By query language
    for lang in ("en", "te"):
        sub = {m: summarise([r for r in rows if r[1] == lang]) for m, rows in per_mode_rows.items()}
        report.append(fmt_table(f"Queries in `{lang}`", sub))

    # 3. Per-query table (hybrid) — the interesting failures live here
    report.append("### Per-query (hybrid): where does it fail?\n")
    report.append("| query | R@5 | MRR | top-1 |\n|---|---|---|---|")
    for r in sorted(per_mode_rows["hybrid"], key=lambda r: r[4]):
        report.append(f"| {r[0]} | {r[2]:.2f} | {r[4]:.2f} | {r[6]} |")
    report.append("")

    # 4. Wins/losses: hybrid vs best single retriever, per query
    wins = losses = ties = 0
    for h, v, k in zip(per_mode_rows["hybrid"], per_mode_rows["vector"], per_mode_rows["keyword"]):
        best = max(v[4], k[4])
        if h[4] > best + 1e-9: wins += 1
        elif h[4] < best - 1e-9: losses += 1
        else: ties += 1
    report.append(f"Hybrid MRR vs best single retriever per query: **{wins} wins / {ties} ties / {losses} losses**\n")

    # 5. Parameter sweep
    if args.sweep:
        sweep = {}
        for k in (10, 30, 60, 100):
            sweep[f"rrfK={k}"] = summarise(run(queries, "hybrid", {"rrfK": k}))
        report.append(fmt_table("Sweep: RRF k (candidateMultiplier=default)", sweep, "setting"))
        sweep = {}
        for mult in (1, 2, 4, 8):
            sweep[f"candidateMultiplier={mult}"] = summarise(run(queries, "hybrid", {"candidateMultiplier": mult}))
        report.append(fmt_table("Sweep: candidate multiplier (rrfK=default)", sweep, "setting"))

    text = "\n".join(report)
    OUT.write_text(text, encoding="utf-8")
    print()
    print(text)
    print(f"\nwritten to {OUT}")


if __name__ == "__main__":
    main()
