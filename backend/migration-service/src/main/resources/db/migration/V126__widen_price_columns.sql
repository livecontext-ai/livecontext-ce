-- V126 - Widen price columns to NUMERIC(14,6)
--
-- Context
-- -------
-- V125 sync hit a NUMERIC(10,4) overflow on a row insert. Max fitting value
-- is $999,999.9999 per 1M tokens - fine for today's per-token LLM pricing
-- (top is around $180/1M), but fragile for future exotic billing modes
-- (per-image, per-character, per-second audio, per-document PDF). The narrow
-- precision was inherited from the initial V109 schema and never revisited.
--
-- This migration widens every per-million-token price column to
-- NUMERIC(14,6): up to 99,999,999.999999, with 6 digits of fractional
-- precision (enough for sub-cent per-token costs). credits_input / credits_output
-- are already NUMERIC(12,4) from V109 and stay as-is - they derive via
-- trigger from price × markup × 10 and are conservative enough.
--
-- The price_input/price_output columns are referenced by the
-- {@code trg_derive_credits} BEFORE trigger ({@code derive_model_credits}
-- function). Postgres refuses to ALTER COLUMN type on a triggered column,
-- so we drop the trigger, widen the columns, and recreate the trigger
-- verbatim - same body, same signature.
--
-- Idempotent: re-running would find the columns already at (14,6) and the
-- ALTER would be a no-op, but the trigger drop/create is NOT idempotent by
-- itself - Flyway's migration-history will prevent a second run anyway.

SET lock_timeout = '10s';
SET statement_timeout = '120s';
SET search_path TO agent;

-- 1. Drop the trigger (its function stays - other callers may not exist but
--    we preserve it for safety; it will be replaced with an identical body
--    in step 3).
DROP TRIGGER IF EXISTS trg_derive_credits ON model_config_overrides;

-- 2. Widen every price column. credits_input / credits_output stay at
--    NUMERIC(12,4) - the trigger still produces values that fit.
ALTER TABLE model_config_overrides
    ALTER COLUMN price_input        TYPE NUMERIC(14,6),
    ALTER COLUMN price_output       TYPE NUMERIC(14,6),
    ALTER COLUMN price_input_batch  TYPE NUMERIC(14,6),
    ALTER COLUMN price_output_batch TYPE NUMERIC(14,6),
    ALTER COLUMN price_cache_read   TYPE NUMERIC(14,6),
    ALTER COLUMN price_cache_write  TYPE NUMERIC(14,6),
    ALTER COLUMN price_floor_input  TYPE NUMERIC(14,6),
    ALTER COLUMN price_floor_output TYPE NUMERIC(14,6);

-- 3. Recreate the trigger with the exact same definition. The underlying
--    function {@code derive_model_credits} is unchanged - only the trigger
--    binding was dropped.
CREATE TRIGGER trg_derive_credits
    BEFORE INSERT OR UPDATE OF price_input, price_output
    ON model_config_overrides
    FOR EACH ROW
    EXECUTE FUNCTION derive_model_credits();

COMMENT ON COLUMN model_config_overrides.price_input IS
    'USD per 1M tokens. NUMERIC(14,6) since V126 - widened from (10,4) after a sync overflow.';
