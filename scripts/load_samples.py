"""Load eval/corpus/sample_docs.json into a running Anvesh instance.

Usage:  python scripts/load_samples.py [http://localhost:8080]
"""
import json, sys, time, urllib.request, pathlib

host = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
corpus = pathlib.Path(__file__).resolve().parent.parent / "eval" / "corpus" / "sample_docs.json"
docs = json.loads(corpus.read_text(encoding="utf-8"))

ids = []
for d in docs:
    req = urllib.request.Request(f"{host}/api/v1/documents", data=json.dumps(d).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req) as r:
        body = json.loads(r.read())
        print(f"{r.status}  {body['id']}  {'(duplicate)' if body['duplicate'] else ''}  {d['title']}")
        ids.append(body["id"])

print("\nWaiting for indexing...")
for _ in range(50):
    statuses = []
    for i in ids:
        with urllib.request.urlopen(f"{host}/api/v1/documents/{i}") as r:
            statuses.append(json.loads(r.read())["status"])
    if all(s == "INDEXED" for s in statuses):
        print("All", len(ids), "documents INDEXED")
        break
    if any(s == "FAILED" for s in statuses):
        print("Some documents FAILED:", statuses); sys.exit(1)
    time.sleep(0.2)
