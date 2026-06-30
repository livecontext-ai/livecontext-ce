-- Drop catalog.apis.credential_mode and its index.
--
-- Background: credential_mode used to gate per-API credential resolution
-- (user_key | platform_key | both). The new contract is simpler:
--   * Workflow nodes carry an explicit `credentialSource` toggle on each Step
--     (frontend CredentialSection.tsx). The catalog honors that strictly,
--     no fallback to the other pool.
--   * Agentic paths (chat, image-gen, embedded agent) get an implicit
--     user-then-platform fallback by default. No per-API knob is needed.
--
-- Backfill: prior audit showed all 609 rows are 'user_key' (no 'both' or
-- 'platform_key' rows in production). 5 api-migrations JSONs declared
-- 'both' but were silently overridden to 'user_key' by the importer's
-- default. No business logic remaining reads the column.
--
-- Spring Data JDBC consideration: ApiEntity no longer maps the column,
-- and PG default 'user_key' covered any insert from older builds during
-- the rollout window.

DROP INDEX IF EXISTS catalog.idx_apis_credential_mode;
ALTER TABLE catalog.apis DROP COLUMN IF EXISTS credential_mode;
