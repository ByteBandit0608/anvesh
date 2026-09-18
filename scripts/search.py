"""Quick search from the terminal (avoids PowerShell's curl/quoting pain).

Usage:  python scripts/search.py "your query" [hybrid|vector|keyword]
"""
import json, sys, urllib.parse, urllib.request

q = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else "hybrid"
url = f"http://localhost:8080/api/v1/search?{urllib.parse.urlencode({'q': q, 'mode': mode, 'limit': 5})}"
with urllib.request.urlopen(url) as r:
    res = json.loads(r.read())
print(f"mode={res['mode']}  hits={res['count']}  took={res['tookMs']}ms\n")
for h in res["hits"]:
    print(f"  {h['score']:.4f}  [{h['title']}]  {h['snippet'][:90]}...")
