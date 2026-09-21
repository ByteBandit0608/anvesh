-- Week 2: store the original body.
-- WHY: chunks overlap (800/100), so the body cannot be reconstructed losslessly from them.
-- Re-indexing (after a model/chunker change, or to retry a FAILED doc) needs the source text.
-- Cost: ≤1 MB per document, TOASTed by Postgres so it doesn't bloat the hot row.
-- Rows ingested before this migration have body = NULL and cannot be re-indexed; re-ingest them.
ALTER TABLE documents ADD COLUMN body TEXT;
