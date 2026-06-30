-- V63: Platform credential markup billing.
--
-- Adds:
--   * Pricing versions and per-endpoint overrides for platform-provisioned credentials.
--   * Per-run pricing pin (freezes pricing at run init so mid-run admin changes don't bill).
--   * Ledger support for markup debits + refunds (related_source_id link, widened source_id).
--   * Markup columns on auth.platform_credentials (default fallback, per-run call cap).
--
-- NOTE: main HEAD at plan-write time is V62 (verified via `git ls-tree origin/main`).
-- Rebase and bump if main advances before this ships.

SET search_path TO auth;

-- 1. Extend platform_credentials with markup defaults.
ALTER TABLE auth.platform_credentials
    ADD COLUMN IF NOT EXISTS default_markup_credits DECIMAL(10,6) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_calls_per_run INTEGER NOT NULL DEFAULT 500;

-- Existing oauth2 rows inherited max_calls_per_run=500 from the column default,
-- which would violate the oauth2_no_markup constraint added below. Zero them
-- out before the constraint is enforced.
UPDATE auth.platform_credentials
SET default_markup_credits = 0,
    max_calls_per_run = 0
WHERE lower(auth_type) = 'oauth2'
  AND (default_markup_credits <> 0 OR max_calls_per_run <> 0);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'platform_credentials_markup_nonneg') THEN
        ALTER TABLE auth.platform_credentials
            ADD CONSTRAINT platform_credentials_markup_nonneg CHECK (default_markup_credits >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'platform_credentials_max_calls_positive') THEN
        ALTER TABLE auth.platform_credentials
            ADD CONSTRAINT platform_credentials_max_calls_positive CHECK (max_calls_per_run >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'platform_credentials_oauth2_no_markup') THEN
        ALTER TABLE auth.platform_credentials
            ADD CONSTRAINT platform_credentials_oauth2_no_markup CHECK (
                lower(auth_type) <> 'oauth2'
                OR (default_markup_credits = 0 AND max_calls_per_run = 0)
            );
    END IF;
END $$;

-- 2. Widen credit_ledger.source_id to 512 and add related_source_id (refund -> debit link).
ALTER TABLE auth.credit_ledger
    ALTER COLUMN source_id TYPE VARCHAR(512);

ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS related_source_id VARCHAR(512);

-- Accelerate sumGrossCostByRunId / sumNetCostByRunId (prefix-match on source_id with user filter).
CREATE INDEX IF NOT EXISTS idx_cl_user_source_prefix
    ON auth.credit_ledger (user_id, source_id text_pattern_ops);

-- 3. Pricing versions.
CREATE TABLE IF NOT EXISTS auth.platform_credential_pricing_version (
    id BIGSERIAL PRIMARY KEY,
    platform_credential_id BIGINT NOT NULL REFERENCES auth.platform_credentials(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    default_markup_credits DECIMAL(10,6) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    CONSTRAINT pricing_version_version_positive CHECK (version > 0),
    CONSTRAINT pricing_version_markup_nonneg CHECK (default_markup_credits >= 0),
    UNIQUE (platform_credential_id, version)
);

CREATE INDEX IF NOT EXISTS idx_pcpv_cred_latest
    ON auth.platform_credential_pricing_version (platform_credential_id, version DESC);

-- 4. Per-endpoint overrides inside a version.
CREATE TABLE IF NOT EXISTS auth.pricing_version_entry (
    id BIGSERIAL PRIMARY KEY,
    pricing_version_id BIGINT NOT NULL REFERENCES auth.platform_credential_pricing_version(id) ON DELETE CASCADE,
    api_tool_id UUID NOT NULL,
    markup_credits DECIMAL(10,6) NOT NULL,
    CONSTRAINT pricing_version_entry_markup_nonneg CHECK (markup_credits >= 0),
    UNIQUE (pricing_version_id, api_tool_id)
);

CREATE INDEX IF NOT EXISTS idx_pve_version_tool
    ON auth.pricing_version_entry (pricing_version_id, api_tool_id);

-- 5. Per-run pricing pin (frozen at run init, read at every debit).
CREATE TABLE IF NOT EXISTS auth.workflow_run_pricing_pin (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    platform_credential_id BIGINT NOT NULL REFERENCES auth.platform_credentials(id) ON DELETE RESTRICT,
    pricing_version_id BIGINT NOT NULL REFERENCES auth.platform_credential_pricing_version(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at TIMESTAMPTZ,
    UNIQUE (run_id, platform_credential_id)
);

CREATE INDEX IF NOT EXISTS idx_wrpp_run ON auth.workflow_run_pricing_pin (run_id);
CREATE INDEX IF NOT EXISTS idx_wrpp_user_live
    ON auth.workflow_run_pricing_pin (user_id)
    WHERE cancelled = FALSE;
-- Sweeper picks rows older than 30 days regardless of cancelled state.
CREATE INDEX IF NOT EXISTS idx_wrpp_created_sweep
    ON auth.workflow_run_pricing_pin (created_at);
