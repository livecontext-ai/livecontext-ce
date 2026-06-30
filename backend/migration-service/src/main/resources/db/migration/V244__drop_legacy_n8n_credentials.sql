-- ============================================================================
-- V244: Drop legacy n8n_credentials table
--
-- Created by V14 (consolidating the old catalog-service-import V002 schema)
-- as a staging table for credential templates. The catalog has since moved to
-- auth.platform_credentials + auth.credentials - no Java/TypeScript code reads
-- or writes n8n_credentials anymore (grep cross-checked May 2026). Dropping
-- to reclaim the dead table from the public schema.
--
-- IF EXISTS makes this a no-op on fresh installs that never had V14 land the
-- table, and CASCADE handles any forgotten FK dependents (none verified, but
-- defensive against external tooling).
-- ============================================================================

SET search_path TO public;

DROP TABLE IF EXISTS n8n_credentials CASCADE;
