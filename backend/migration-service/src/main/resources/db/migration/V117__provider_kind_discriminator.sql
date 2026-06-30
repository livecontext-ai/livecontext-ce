-- Provider-kind discriminator: how a provider is consumed, orthogonal to
-- model_config_overrides.source (catalog origin: manual/curated/bundle/…).
--
--   cloud   - call goes through LiveContext cloud proxy, credits debited from
--             linked admin cloud account.
--   byok    - admin entered an API key in /settings/ai-providers (or env var);
--             call goes direct to the provider.
--   bridge  - call routed through the local CLI bridge (Claude Code, Codex,
--             Gemini CLI, Mistral Vibe) on websearch-host:8093. Shared CLI
--             session; ACL gated by auth.bridge_access_policy (see V118).
--
-- Backfill rule: the 4 known CLI bridges are flipped to 'bridge'; everything
-- else defaults to 'byok'. 'cloud' is only written by CloudProxyProvider when
-- the admin links the cloud (future phase).

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- agent.model_config_overrides -----------------------------------------------

SET search_path TO agent;

ALTER TABLE model_config_overrides
    ADD COLUMN IF NOT EXISTS provider_kind VARCHAR(16) NOT NULL DEFAULT 'byok';

UPDATE model_config_overrides
   SET provider_kind = 'bridge'
 WHERE provider IN ('claude-code', 'codex', 'gemini-cli', 'mistral-vibe')
   AND provider_kind <> 'bridge';

ALTER TABLE model_config_overrides
    DROP CONSTRAINT IF EXISTS model_config_overrides_provider_kind_check,
    ADD  CONSTRAINT model_config_overrides_provider_kind_check
         CHECK (provider_kind IN ('cloud', 'byok', 'bridge'));

CREATE INDEX IF NOT EXISTS idx_model_config_provider_kind
    ON model_config_overrides(provider_kind, provider);

COMMENT ON COLUMN model_config_overrides.provider_kind IS
    'How the provider is consumed: cloud (via LiveContext proxy), byok (admin API key), bridge (CLI). Orthogonal to the source column which tracks catalog origin.';

-- auth.model_pricing ---------------------------------------------------------
-- Mirror the discriminator so cost accounting can skip bridge rows (flat-rate
-- via CLI subscription, per-token $/1M is meaningless).

SET search_path TO auth;

ALTER TABLE model_pricing
    ADD COLUMN IF NOT EXISTS provider_kind VARCHAR(16) NOT NULL DEFAULT 'byok';

UPDATE model_pricing
   SET provider_kind = 'bridge'
 WHERE provider IN ('claude-code', 'codex', 'gemini-cli', 'mistral-vibe')
   AND provider_kind <> 'bridge';

ALTER TABLE model_pricing
    DROP CONSTRAINT IF EXISTS model_pricing_provider_kind_check,
    ADD  CONSTRAINT model_pricing_provider_kind_check
         CHECK (provider_kind IN ('cloud', 'byok', 'bridge'));

-- Bridge rows: zero the per-token rates (they are flat-rate). Keep the rows
-- (Finance may want to query historical bridge usage) but their input/output
-- rates must not drive credit debits.
UPDATE model_pricing
   SET input_rate = 0, output_rate = 0
 WHERE provider_kind = 'bridge'
   AND (input_rate <> 0 OR output_rate <> 0);

CREATE INDEX IF NOT EXISTS idx_model_pricing_provider_kind_active
    ON model_pricing(provider_kind, provider, model)
    WHERE is_active = TRUE;

COMMENT ON COLUMN model_pricing.provider_kind IS
    'Mirror of agent.model_config_overrides.provider_kind. Rows with kind=bridge carry zero rates (flat-rate via CLI subscription).';
