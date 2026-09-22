-- ---------------------------------------------------------------------------
-- V508: Turn a whole AI provider off in one move
--
-- Until now the only switch was per model. A provider synced from a feed carries
-- hundreds of rows (OpenRouter alone is 438, Mistral 60), so removing one from the
-- pickers meant hundreds of clicks, and re-enabling it meant remembering which of
-- those rows had been off to begin with.
--
-- This table holds the provider-level answer, and only the EXCEPTIONS: a provider
-- with no row here is enabled. Disabling one hides every model it serves from every
-- picker at once, and leaves each model's own `enabled` flag untouched, so flipping
-- the provider back on restores exactly the selection the admin had curated.
--
-- Resolution (read in ModelCatalogService.getModelsForCategory):
--   - row present with enabled = FALSE  → the provider and all its models vanish
--   - row present with enabled = TRUE   → same as no row
--   - row absent                        → enabled
--
-- Deliberately NOT applied to the admin catalogue (getEffectiveModelList): the
-- Models panel has to keep listing a disabled provider, or there would be no way
-- to switch it back on.
-- ---------------------------------------------------------------------------

SET search_path = agent, public;

CREATE TABLE IF NOT EXISTS model_provider_settings (
    provider   VARCHAR(64) PRIMARY KEY,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same shape the catalogue uses for a provider name everywhere else: lower-case,
    -- and the hyphen is real (claude-code, gemini-cli, mistral-vibe).
    CONSTRAINT model_provider_settings_name_shape_chk
        CHECK (provider ~ '^[a-z][a-z0-9_-]*$')
);

COMMENT ON TABLE model_provider_settings IS
    'Provider-level enable switch. Only exceptions are stored: no row means enabled.';
