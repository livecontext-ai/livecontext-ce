-- PR5 - Full-text + trigram search on workflow_publications.
--
-- Before this migration, `application(action='search', query=...)` used
-- `LOWER(search_text) LIKE LOWER('%' || :q || '%')` - full table scan, no typo
-- tolerance, no relevance ranking. After: GIN-indexed tsvector + pg_trgm fallback,
-- mirrors the catalog V159 pattern that's been running in prod since 2026-03.
--
-- LOCK POSTURE
-- ============
-- `ADD COLUMN ... GENERATED ALWAYS AS (...) STORED` takes an AccessExclusiveLock
-- on the table AND rewrites every row to populate the new column. On the current
-- prod publications table (~1k rows with ~5KB search_text each) this completes in
-- <10s. If the table grows past ~100k rows, this single-statement pattern becomes
-- risky - split into (a) ADD COLUMN NULL, (b) UPDATE in chunks, (c) ALTER COLUMN
-- SET GENERATED. Not needed at current scale.
--
-- The two GIN indexes use CREATE INDEX CONCURRENTLY so writes are not blocked
-- during the build phase; only the ALTER TABLE itself holds the exclusive lock
-- briefly. Pattern matches V204/V208-V214/V218/V225 in this repo.
--
-- SEARCH_PATH
-- ===========
-- pg_trgm is installed in the `catalog` schema (V12:37, ALTER EXTENSION
-- pg_trgm SET SCHEMA catalog). For `similarity()` / `gin_trgm_ops` to resolve
-- inside this migration without explicit qualification, we SET search_path so
-- Postgres can find them. The session-scoped SET stays for the duration of the
-- Flyway connection (executeInTransaction=false → each statement runs in its
-- own implicit tx but the session/search_path persists).

-- flyway:executeInTransaction=false

SET search_path = publication, catalog, public;

-- 1. Generated tsvector column. Dual-config (english + simple) gives us EN
--    stemming ("emails" → "email", "sending" → "send") while preserving
--    verbatim tokens for brand names, French content, and other non-English
--    languages that the simple parser handles correctly (no language-specific
--    stemming, no stopwords). Weights: A = title + category (highest signal),
--    B = description, C = denormalized nested content from SearchTextBuilder.
ALTER TABLE publication.workflow_publications
  ADD COLUMN IF NOT EXISTS tsv_search tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title, '')),         'A') ||
    setweight(to_tsvector('simple',  coalesce(title, '')),         'A') ||
    setweight(to_tsvector('english', coalesce(category_name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(description, '')),   'B') ||
    setweight(to_tsvector('simple',  coalesce(description, '')),   'B') ||
    setweight(to_tsvector('simple',  coalesce(search_text, '')),   'C')
  ) STORED;

-- 2. GIN index on the tsvector for fast @@ tsquery matching.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pubs_tsv_search
  ON publication.workflow_publications USING GIN(tsv_search);

-- 3. GIN trigram index on search_text for typo-tolerant fuzzy fallback.
--    `gin_trgm_ops` lives in the `catalog` schema (V12) - the search_path SET
--    above makes it resolvable here. Threshold 0.1 matches catalog's repo
--    (LexicalSearchIndexRepository) for cross-tool consistency.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pubs_search_text_trgm
  ON publication.workflow_publications USING GIN(search_text gin_trgm_ops);
