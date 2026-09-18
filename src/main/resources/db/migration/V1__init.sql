-- Anvesh schema v1
-- Two retrievers share one table of chunks:
--   * vector search   -> chunks.embedding  (pgvector, HNSW index, cosine distance)
--   * keyword search  -> chunks.tsv        (Postgres full-text, GIN index)
-- Results are fused in application code with Reciprocal Rank Fusion.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid()

CREATE TABLE documents (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title        TEXT        NOT NULL,
    source       TEXT,                          -- URL / filename / "api"
    language     VARCHAR(8)  NOT NULL DEFAULT 'und',  -- BCP-47-ish: 'en', 'te', 'und'
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    content_hash CHAR(64)    NOT NULL,          -- sha256 of body -> idempotent ingestion
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING | INDEXED | FAILED
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    indexed_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_documents_content_hash ON documents (content_hash);
CREATE INDEX ix_documents_status ON documents (status);
CREATE INDEX ix_documents_metadata ON documents USING GIN (metadata);

CREATE TABLE chunks (
    id           BIGSERIAL PRIMARY KEY,
    document_id  UUID    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    ordinal      INT     NOT NULL,              -- position within the document
    content      TEXT    NOT NULL,
    token_count  INT,
    embedding    vector(384),                   -- must match anvesh.embedding.dimension
    -- 'simple' config is language-agnostic (no English stemming), which is the
    -- right default for mixed English/Telugu text. Week 4 idea: per-language configs.
    tsv          tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    UNIQUE (document_id, ordinal)
);

-- Approximate nearest-neighbour index. Cosine because embeddings are L2-normalised.
CREATE INDEX ix_chunks_embedding_hnsw
    ON chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX ix_chunks_tsv ON chunks USING GIN (tsv);
CREATE INDEX ix_chunks_document ON chunks (document_id);
