-- V135: Allow null default_markup_credits on pricing versions.
--
-- A pricing version may now represent "no API-wide default - only per-tool
-- overrides apply". Tools without an override in pricing_version_entry are
-- therefore free instead of falling back to a default rate. This lets admins
-- monetise a single endpoint (e.g. gmail.send_message) without implicitly
-- pricing every other endpoint of the same API.
--
-- MarkupPolicy.resolveEffectiveMarkup already treats a null default as
-- BigDecimal.ZERO at the read boundary, so existing runtime paths stay safe.

SET search_path TO auth;

ALTER TABLE auth.platform_credential_pricing_version
    ALTER COLUMN default_markup_credits DROP NOT NULL;

-- Drop the old non-negative CHECK and re-add it in a NULL-tolerant form.
-- `CHECK (x >= 0)` already allows NULL in Postgres, but we replace the
-- constraint explicitly so its intent is obvious in \d+ and so that older
-- migration log readers don't assume NOT NULL is still in force.
ALTER TABLE auth.platform_credential_pricing_version
    DROP CONSTRAINT IF EXISTS pricing_version_markup_nonneg;

ALTER TABLE auth.platform_credential_pricing_version
    ADD CONSTRAINT pricing_version_markup_nonneg
    CHECK (default_markup_credits IS NULL OR default_markup_credits >= 0);
