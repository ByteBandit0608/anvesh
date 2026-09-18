"""Delete every document (and its chunks, via ON DELETE CASCADE).

Needed whenever the embedding model changes: vectors from two different models live in
incompatible spaces, so mixing them makes similarity meaningless. Re-ingest afterwards.

Usage:  python scripts/reset_index.py [http://localhost:8080]
"""
import json, sys, urllib.request

host = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
deleted = 0
while True:
    with urllib.request.urlopen(f"{host}/api/v1/documents?limit=100") as r:
        docs = json.loads(r.read())
    if not docs:
        break
    for d in docs:
        req = urllib.request.Request(f"{host}/api/v1/documents/{d['id']}", method="DELETE")
        urllib.request.urlopen(req).close()
        deleted += 1
print(f"Deleted {deleted} documents")
