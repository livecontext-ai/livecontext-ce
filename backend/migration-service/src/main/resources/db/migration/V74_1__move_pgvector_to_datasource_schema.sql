-- V12 can install pgvector in the catalog schema before datasource migrations run.
-- V74 then becomes a no-op because CREATE EXTENSION IF NOT EXISTS does not move
-- an already-installed extension. Move the extension before V75 creates
-- datasource.data_source_vectors with search_path=datasource.
--
-- Idempotency: ALTER EXTENSION requires extension ownership (= postgres superuser
-- on managed prod). Replaying this on an env where vector is ALREADY in
-- datasource raises "must be owner of extension vector" and breaks every
-- subsequent Flyway run for the schema (2026-05-22 incident: V280+V281
-- couldn't apply because V74_1 was the head of the out-of-order chain). The
-- pre-ALTER check now skips the rewrite when vector is already where we want
-- it, so the migration is a true no-op for envs that don't need the move.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_extension e
        JOIN pg_namespace n ON e.extnamespace = n.oid
        WHERE e.extname = 'vector' AND n.nspname <> 'datasource'
    ) AND EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'datasource') THEN
        ALTER EXTENSION vector SET SCHEMA datasource;
    END IF;
END $$;
