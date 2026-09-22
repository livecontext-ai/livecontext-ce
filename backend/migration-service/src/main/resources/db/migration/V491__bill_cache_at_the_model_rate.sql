-- Bill cache tokens at the MODEL's own rate instead of a per-family multiplier.
--
-- Why. ModelPricingService weighted cache tokens with five hardcoded per-family
-- constants (Anthropic write 1.25x / read 0.1x, OpenAI 0.5x, Gemini 0.25x, DeepSeek
-- 0.1x) because auth.model_pricing only ever carried input_rate/output_rate. Those
-- constants were a 2024-era approximation and the catalog has since learned the real
-- per-model prices: agent.model_config_overrides.price_cache_read/price_cache_write,
-- fed by the LiteLLM/OpenRouter feeds. Measured on the production catalog, 195 of the
-- 239 models that carry a feed cache price were billed at the wrong rate - Claude
-- Fable 5.1 reads cache at 0.025x input and was billed 0.1x (x4), codex reads at 0.1x
-- and was billed 0.5x (x5), Gemini reads at 0.1x and was billed 0.25x (x2.5) - while
-- qwen/moonshot/minimax/mistral had no family at all and were billed full input rate.
-- The multiplier billing.llm.cloud-multiplier is supposed to be the ONLY margin; it
-- cannot be while a second, invisible factor rides on the cache line.
--
-- NULL means "this model has no known cache price", and ModelPricingService then falls
-- back to the family multiplier exactly as before, so a row this migration cannot fill
-- keeps its current behaviour.
ALTER TABLE auth.model_pricing
    ADD COLUMN IF NOT EXISTS cache_read_rate  NUMERIC(10,6),
    ADD COLUMN IF NOT EXISTS cache_write_rate NUMERIC(10,6);

COMMENT ON COLUMN auth.model_pricing.cache_read_rate IS
    'Provider USD per 1M cached-input tokens. NULL = unknown, bill at input_rate x the family multiplier.';
COMMENT ON COLUMN auth.model_pricing.cache_write_rate IS
    'Provider USD per 1M cache-creation tokens (Anthropic-style additive writes). NULL = unknown, bill at input_rate x the family multiplier.';

-- Backfill the mirror from the catalog it mirrors. The steady-state path is
-- AuthPricingSyncClient (admin edit + bundle apply), but that only fires when a price
-- CHANGES, so without this every row already in the table would keep billing on the
-- family multiplier until someone happened to re-price it.
--
-- Cross-schema on purpose: migration-service is the one component that owns every
-- schema (see scripts/ci/check-cross-schema-sql.py, which exempts migrations for
-- exactly this reason).
--
-- Two guards on what is copied, both of which the steady-state sync path
-- (AuthPricingSyncClient.storableCacheRate) already applies to the same values:
--
--   * STRICTLY POSITIVE. The catalog stores 0 for "this provider does not charge for
--     cache writes" (every DeepSeek row does), and 0 here would not mean that - it
--     would mean the cache is FREE. NULL is the only honest way to say "unknown", and
--     ModelPricingService then falls back to the family multiplier.
--   * WITHIN RANGE. The source column is NUMERIC(14,6), the target NUMERIC(10,6). A
--     sentinel feed price (openrouter/auto ships -1, which becomes -1000000 after the
--     per-million scaling) would abort this migration on a numeric overflow, and with
--     auth-service on ddl-auto:validate an aborted migration means the auth pods do not
--     boot. That exact value is why storableCacheRate exists; the migration must not
--     re-open the hole it closes.
UPDATE auth.model_pricing mp
SET cache_read_rate  = COALESCE(mp.cache_read_rate,
                                CASE WHEN mco.price_cache_read BETWEEN 0.000001 AND 9999.999999
                                     THEN mco.price_cache_read END),
    cache_write_rate = COALESCE(mp.cache_write_rate,
                                CASE WHEN mco.price_cache_write BETWEEN 0.000001 AND 9999.999999
                                     THEN mco.price_cache_write END)
FROM agent.model_config_overrides mco
WHERE mp.provider = mco.provider
  AND mp.model = mco.model_id
  AND mp.is_active = true
  AND (mco.price_cache_read BETWEEN 0.000001 AND 9999.999999
    OR mco.price_cache_write BETWEEN 0.000001 AND 9999.999999);
