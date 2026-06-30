-- V130 - Backfill bridge rows with underlying-model prices
--
-- Context
-- -------
-- V128 seeded bridge catalog rows with price_input=0 / price_output=0, on
-- the assumption that bridges are flat-rate via CLI subscription. Product
-- decision (2026-04): bridge rows MUST carry the price of the underlying
-- cloud model, so:
--   * admin UI shows the "would-be" cost for reporting & fairness display;
--   * future markup rules can layer on top;
--   * reports aren't biased by zero-rate rows.
--
-- The per-token debit behaviour is still governed by auth-service's gate
-- (V117) which treats provider_kind='bridge' specially for credit
-- accounting. Prices here are metadata; they do not automatically bill.
--
-- Going forward, {@code BridgeModelDeriver} keeps these prices in sync
-- with LiteLLM every sync cycle. This migration is a one-shot backfill
-- to get existing rows to a good state BEFORE the next sync-apply.
--
-- Prices sourced from LiteLLM snapshot 2026-04-22 (published provider
-- list prices; verify against each provider's official pricing page
-- before activating a bundle).

SET lock_timeout = '10s';
SET statement_timeout = '30s';
SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- 1. agent.model_config_overrides - bridge rows.
-- ---------------------------------------------------------------------------

-- Claude Code
UPDATE model_config_overrides SET price_input = 5,    price_output = 25   WHERE provider = 'claude-code' AND model_id IN ('claude-opus-4-7', 'claude-opus-4-6');
UPDATE model_config_overrides SET price_input = 3,    price_output = 15   WHERE provider = 'claude-code' AND model_id IN ('claude-sonnet-4-6', 'claude-sonnet-4-5');
UPDATE model_config_overrides SET price_input = 1,    price_output = 5    WHERE provider = 'claude-code' AND model_id = 'claude-haiku-4-5';

-- Codex
UPDATE model_config_overrides SET price_input = 2.5,  price_output = 15   WHERE provider = 'codex' AND model_id = 'gpt-5.4';
UPDATE model_config_overrides SET price_input = 0.75, price_output = 4.5  WHERE provider = 'codex' AND model_id = 'gpt-5.4-mini';
UPDATE model_config_overrides SET price_input = 1.75, price_output = 14   WHERE provider = 'codex' AND model_id IN ('gpt-5.3-codex', 'gpt-5.2');

-- Gemini CLI
UPDATE model_config_overrides SET price_input = 2,    price_output = 12   WHERE provider = 'gemini-cli' AND model_id IN ('gemini-3.1-pro-preview', 'gemini-3-flash-preview');
UPDATE model_config_overrides SET price_input = 1.25, price_output = 10   WHERE provider = 'gemini-cli' AND model_id = 'gemini-2.5-pro';
UPDATE model_config_overrides SET price_input = 0.10, price_output = 0.40 WHERE provider = 'gemini-cli' AND model_id = 'gemini-2.5-flash';

-- Mistral Vibe (devstral-latest pricing as of 2026-04)
UPDATE model_config_overrides SET price_input = 2,    price_output = 6    WHERE provider = 'mistral-vibe' AND model_id = 'devstral-2';
UPDATE model_config_overrides SET price_input = 0.40, price_output = 2    WHERE provider = 'mistral-vibe' AND model_id = 'devstral-small-2';

-- ---------------------------------------------------------------------------
-- 2. auth.model_pricing - mirror the same prices so CreditService sees them.
-- ---------------------------------------------------------------------------

UPDATE auth.model_pricing SET input_rate = 5,    output_rate = 25   WHERE provider = 'claude-code' AND model IN ('claude-opus-4-7', 'claude-opus-4-6');
UPDATE auth.model_pricing SET input_rate = 3,    output_rate = 15   WHERE provider = 'claude-code' AND model IN ('claude-sonnet-4-6', 'claude-sonnet-4-5');
UPDATE auth.model_pricing SET input_rate = 1,    output_rate = 5    WHERE provider = 'claude-code' AND model = 'claude-haiku-4-5';

UPDATE auth.model_pricing SET input_rate = 2.5,  output_rate = 15   WHERE provider = 'codex' AND model = 'gpt-5.4';
UPDATE auth.model_pricing SET input_rate = 0.75, output_rate = 4.5  WHERE provider = 'codex' AND model = 'gpt-5.4-mini';
UPDATE auth.model_pricing SET input_rate = 1.75, output_rate = 14   WHERE provider = 'codex' AND model IN ('gpt-5.3-codex', 'gpt-5.2');

UPDATE auth.model_pricing SET input_rate = 2,    output_rate = 12   WHERE provider = 'gemini-cli' AND model IN ('gemini-3.1-pro-preview', 'gemini-3-flash-preview');
UPDATE auth.model_pricing SET input_rate = 1.25, output_rate = 10   WHERE provider = 'gemini-cli' AND model = 'gemini-2.5-pro';
UPDATE auth.model_pricing SET input_rate = 0.10, output_rate = 0.40 WHERE provider = 'gemini-cli' AND model = 'gemini-2.5-flash';

UPDATE auth.model_pricing SET input_rate = 2,    output_rate = 6    WHERE provider = 'mistral-vibe' AND model = 'devstral-2';
UPDATE auth.model_pricing SET input_rate = 0.40, output_rate = 2    WHERE provider = 'mistral-vibe' AND model = 'devstral-small-2';

-- ---------------------------------------------------------------------------
-- 3. Scrub zero-priced cloud rows inserted by earlier sync-apply runs.
-- ---------------------------------------------------------------------------
-- V3.1 parsers now reject these at the feed boundary. This cleanup removes
-- legacy rows with both prices = 0 so they don't linger in the picker.

DELETE FROM agent.model_config_overrides
 WHERE provider_kind != 'bridge'
   AND price_input = 0
   AND price_output = 0;

DELETE FROM auth.model_pricing
 WHERE provider_kind != 'bridge'
   AND input_rate = 0
   AND output_rate = 0;
