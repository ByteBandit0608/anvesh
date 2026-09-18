# Evaluation harness (Week 4 deliverable)

Goal: turn "it works" into numbers you can put on a resume.

1. Put ~30–50 documents in `eval/corpus/` (Wikipedia paragraphs in English + Telugu, your own notes, README files).
2. For each query in `queries.jsonl`, list which document titles are relevant.
3. Write `eval/run_eval.py` (or a Java `@SpringBootTest`) that:
   - ingests the corpus,
   - runs every query in `vector`, `keyword` and `hybrid` mode,
   - computes **Recall@5**, **Recall@10** and **MRR** per mode,
   - prints a table.
4. Expected outcome: hybrid ≥ max(vector, keyword) on most queries. Where it isn't, investigate —
   that investigation is exactly the kind of thing interviewers love to hear about.

Also record **p50 / p95 latency** per mode from `/actuator/prometheus` (`anvesh_search_seconds`).
