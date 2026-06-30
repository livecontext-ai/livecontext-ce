-- V145__remove_webhook_mode.sql
-- =============================================================================
-- Remove the 'webhook' execution mode from catalog.api_tools.
--
-- Context:
--   V52 introduced the typed-execution model with mode IN
--   (sync | async_poll | upload | streaming | webhook). The webhook mode was
--   reserved for callback-driven flows but never had a runtime implementation:
--   HttpExecutionService rejected it explicitly, validate_apis.py rejected it
--   at import, and FullCatalogImportE2ETest asserts webhookCount=0 in the seed.
--
--   The mode is being retired entirely so the schema, validators, and runtime
--   stay aligned. No production tool declares it (V52 hardening since 2026-04
--   has prevented insertion).
--
-- Safety:
--   - The defensive UPDATE coerces any legacy `execution_mode='webhook'` row
--     to 'sync' (only relevant on developer laptops that imported test JSON
--     before validators were tightened). RAISE NOTICE leaves a forensic trace
--     in the Flyway log when this fires.
--   - The CHECK constraint is dropped and re-added without 'webhook'. Forward
--     inserts will fail with a clear PG error if any code path tries it.
--
-- Rollback:
--   A V146 file restoring 'webhook' to the CHECK is staged locally on the PR
--   branch but is NOT committed. It will be promoted only on a confirmed prod
--   incident (>1 catalog import failure tied to this constraint).
-- =============================================================================

SET search_path TO catalog, public;

DO $$
DECLARE
    coerced_count int;
BEGIN
    UPDATE api_tools SET execution_mode = 'sync' WHERE execution_mode = 'webhook';
    GET DIAGNOSTICS coerced_count = ROW_COUNT;
    IF coerced_count > 0 THEN
        RAISE NOTICE 'V145: coerced % api_tools rows from webhook to sync (dev environment cleanup)', coerced_count;
    END IF;
END $$;

ALTER TABLE api_tools DROP CONSTRAINT IF EXISTS check_api_tools_execution_mode;
ALTER TABLE api_tools ADD CONSTRAINT check_api_tools_execution_mode
    CHECK (execution_mode IS NULL OR execution_mode IN
        ('sync', 'async_poll', 'upload', 'streaming'));

COMMENT ON COLUMN api_tools.execution_mode IS
    'Denormalized from execution_spec.mode for indexed lookups. One of: sync | async_poll | upload | streaming.';
