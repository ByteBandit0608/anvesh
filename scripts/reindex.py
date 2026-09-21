"""Re-index one document, or every document, via POST /documents/{id}/reindex.

  python scripts/reindex.py <id>      # one
  python scripts/reindex.py --all     # all (after a model change)
"""
import json, sys, time, urllib.request, urllib.error

BASE = "http://localhost:8080/api/v1"

def post(path):
    req = urllib.request.Request(BASE + path, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

def get(path):
    with urllib.request.urlopen(BASE + path) as r:
        return json.loads(r.read())

def wait(doc_id, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        d = get(f"/documents/{doc_id}")
        if d["status"] != "PENDING":
            return d["status"], d.get("error")
        time.sleep(0.3)
    return "TIMEOUT", None

if len(sys.argv) < 2:
    print(__doc__); sys.exit(1)

ids = [d["id"] for d in get("/documents?limit=100")] if sys.argv[1] == "--all" else [sys.argv[1]]
for i in ids:
    code, body = post(f"/documents/{i}/reindex")
    if code != 202:
        print(f"{i}  -> HTTP {code}: {body.get('message')}"); continue
    status, err = wait(i)
    print(f"{i}  -> {status}" + (f"  ({err})" if err else ""))
