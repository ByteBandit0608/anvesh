-- Week 4: multi-tenant API keys + owner_id on documents
-- WHY owner_id TEXT not UUID: owner is derived from api_keys.owner_id which is a tenant string.
-- For anonymous / public access we use 'public' (see RequestContext). TEXT keeps it simple and
-- avoids a join for every search.

CREATE TABLE api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash     CHAR(64) NOT NULL UNIQUE,          -- SHA-256 hex of raw key, never store raw
    name         TEXT NOT NULL,                      -- human label e.g. "bhavya-laptop"
    owner_id     TEXT NOT NULL,                      -- tenant id, e.g. hash of name or user id
    is_active    BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX ix_api_keys_owner ON api_keys(owner_id);

-- Add owner_id to documents, backfill existing rows as 'public' for backward compat.
ALTER TABLE documents ADD COLUMN owner_id TEXT;
UPDATE documents SET owner_id = 'public' WHERE owner_id IS NULL;
ALTER TABLE documents ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE documents ALTER COLUMN owner_id SET DEFAULT 'public';
CREATE INDEX ix_documents_owner ON documents(owner_id);

-- Content hash uniqueness should be per-tenant: two tenants can ingest same body independently.
-- Drop global unique and recreate as (owner_id, content_hash).
DROP INDEX IF EXISTS ux_documents_content_hash;
CREATE UNIQUE INDEX ux_documents_owner_hash ON documents(owner_id, content_hash);
