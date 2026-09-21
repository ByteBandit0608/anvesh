"""Ingest throughput benchmark: how many docs/sec does the pipeline sustain, and does
serialising ONNX inference (ONNX_SERIALIZE=true) help or hurt on *your* CPU?

  python scripts/bench_ingest.py [--docs 40] [--batch]

Run it twice: once with the app started normally, once with $env:ONNX_SERIALIZE="true".
Write both numbers into docs/EMBEDDINGS.md. Requires a running app (localhost:8080).
Documents are unique per run (UUID salt) so SHA-256 dedup doesn't short-circuit them,
and are deleted at the end.
"""
import argparse, json, time, urllib.request, uuid, statistics

BASE = "http://localhost:8080/api/v1"
PARA_EN = ("Electricity demand in Telangana peaks in the summer months when air-conditioning load rises "
           "sharply across Hyderabad. Utilities schedule maintenance for the monsoon and buy short-term power "
           "on the exchange to cover the gap. ")
PARA_TE = ("వేసవి కాలంలో ఉష్ణోగ్రత పెరిగినప్పుడు తెలంగాణలో విద్యుత్ డిమాండ్ గరిష్ట స్థాయికి చేరుతుంది. "
           "హైదరాబాద్‌లో ఎయిర్ కండిషనర్ల వాడకం పెరగడం ప్రధాన కారణం. ")

def http(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--docs", type=int, default=40)
    ap.add_argument("--batch", action="store_true", help="use POST /documents/batch instead of 1 call per doc")
    a = ap.parse_args()

    salt = uuid.uuid4().hex[:8]
    docs = []
    for i in range(a.docs):
        para = PARA_TE if i % 2 else PARA_EN
        docs.append({"title": f"bench-{salt}-{i}", "language": "te" if i % 2 else "en",
                     "body": (para * 6) + f" [{salt}-{i}]"})       # ~1.3 KB -> 2–3 chunks each

    t0 = time.perf_counter()
    if a.batch:
        ids = [r["id"] for r in http("POST", "/documents/batch", {"documents": docs})["results"]]
    else:
        ids = [http("POST", "/documents", d)["id"] for d in docs]
    t_submit = time.perf_counter() - t0

    pending = set(ids)
    while pending:
        for i in list(pending):
            s = http("GET", f"/documents/{i}")["status"]
            if s == "INDEXED": pending.discard(i)
            elif s == "FAILED": raise SystemExit(f"{i} FAILED")
        time.sleep(0.1)
    t_total = time.perf_counter() - t0

    # Per-document index time from the Micrometer timer.
    prom = urllib.request.urlopen("http://localhost:8080/actuator/prometheus").read().decode()
    timer = {k.split("{")[0]: v for k, v in
             (l.rsplit(" ", 1) for l in prom.splitlines() if l.startswith("anvesh_ingest_index_seconds_"))}
    count = float(timer.get("anvesh_ingest_index_seconds_count", 0))
    total = float(timer.get("anvesh_ingest_index_seconds_sum", 0))

    print(f"docs={a.docs}  mode={'batch' if a.batch else 'single'}")
    print(f"submit phase : {t_submit:6.2f} s   ({a.docs / t_submit:6.1f} docs/s accepted)")
    print(f"end-to-end   : {t_total:6.2f} s   ({a.docs / t_total:6.1f} docs/s INDEXED)  <-- the resume number")
    if count:
        print(f"mean index() : {1000 * total / count:6.0f} ms/doc (lifetime avg from /actuator/prometheus)")

    for i in ids: http("DELETE", f"/documents/{i}")
    print("cleaned up")

if __name__ == "__main__":
    main()
