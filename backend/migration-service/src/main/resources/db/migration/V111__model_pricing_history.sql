-- Append-only history for auth.model_pricing.
-- upsertPricing() now flips the previous active row (effective_to=today, is_active=false)
-- and INSERTs a new row in the same TX, instead of in-place UPDATE.
-- Finance can reconstruct pricing at any past date.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

ALTER TABLE auth.model_pricing
    ADD COLUMN IF NOT EXISTS effective_to DATE;

CREATE INDEX IF NOT EXISTS idx_model_pricing_active_lookup
    ON auth.model_pricing(provider, model)
    WHERE is_active = TRUE;

COMMENT ON COLUMN auth.model_pricing.effective_to IS
    'Last day this rate was active (inclusive). NULL = still active. Set when a newer row supersedes this one.';
