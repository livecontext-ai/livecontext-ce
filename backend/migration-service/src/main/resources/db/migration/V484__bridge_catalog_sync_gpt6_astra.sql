-- V484 - Bridge catalog sync: GPT-6 Astra + GPT-5.4 Nano for codex
--
-- Context
-- -------
-- codex is curated-only (see BridgeAllowlist.DISCOVERY_PATTERNS): its
-- DISCOVERY_PATTERNS entry was removed by e399615a4/V399 because OpenAI's
-- Codex-routable set is not derivable from the openai feed. That feed carries
-- bare gpt-5.x ids Codex refuses with a ChatGPT account (typed 400), and the
-- ids Codex DOES route are codenamed (sol/terra/luna, and now astra), which no
-- numeric pattern can predict. So every codex id ships by hand, here.
--
-- The gap, measured against the LIVE prod catalog on 2026-09-09:
--   openai (API side) : gpt-6-astra, gpt-5.4-nano, gpt-5.4-mini, gpt-5.4,
--                       gpt-5.5, gpt-5.6-sol/terra/luna
--   codex  (CLI side) : gpt-5.4, gpt-5.5, gpt-5.6-sol/terra/luna
--
-- Three ids differ, and they are NOT the same kind of problem:
--   * gpt-6-astra  - new generation, never allow-listed, never seeded by any
--                    migration. Genuinely absent. Seeded here.
--   * gpt-5.4-nano - never allow-listed (V128 seeded 5.2 / 5.3-codex / 5.4 /
--                    5.4-mini only), so never seeded. Genuinely absent.
--                    Seeded here.
--   * gpt-5.4-mini - NOT absent. V128:75 seeds the catalog row, V128:107 and
--                    V130:40/61 price it 0.75/4.5, and V441:83 updates it in a
--                    statement whose header says "measured against production
--                    2026-08-24", so the row demonstrably exists in prod. It is
--                    listed in the codex YAML CSV, and the picker's YAML branch
--                    drops a row only on enabled=false
--                    (ModelCatalogService.getModelsForCategory), which is also
--                    why gpt-5.2 and gpt-5.3-codex are absent from the live
--                    list. It is therefore an operator decision, not a missing
--                    row, and this migration deliberately does NOT touch it:
--                    forcing enabled=TRUE would silently reverse that decision.
--                    Re-enabling it is one click in the admin Models panel.
--
-- Deliberately NOT re-seeded either: gpt-5.4, gpt-5.5 and the three 5.6 tiers
-- already exist and carry feed-synced prices that have moved since V399 (sol is
-- now 4/20, not the 5/30 V399 wrote). Re-inserting a snapshot over them would
-- walk those prices backwards. The INSERT below covers ONLY the two genuinely
-- missing ids; the reconciling DELETE still names the full curated set.
--
-- Pricing: the underlying openai list prices read from the live catalog on
-- 2026-09-09 (same convention as V130/V378/V399, a bridge row carries the cloud
-- price). Unlike those, do NOT assume a later refresh corrects these two:
-- BridgeModelDeriver only looks curated ids up in the LiteLLM feed
-- (ModelCatalogSyncService passes litellm.models(), never the native-discovery
-- rows appended later in the same pass), so an id the feed does not carry is
-- skipped as missingUnderlying and its rate is never refreshed. These rows bill
-- for real: CreditService has no provider_kind='bridge' short-circuit, a bridge
-- turn debits at the same per-token rate as the cloud route.
--
-- Paired change (tri-parity guard BridgeProvidersHavePricingTest lists this
-- file, and that guard is itself newly wired into CI by this commit):
-- BridgeAllowlist.MODELS[codex] += gpt-6-astra, gpt-5.4-nano
--      + agent-service/application.yml providers.codex.models
--      + monolith-service/application-ce.yml providers.codex.models.
--
-- Additive + idempotent: ON CONFLICT DO UPDATE realigns a row a feed sync may
-- already have created.

SET lock_timeout = '10s';
SET statement_timeout = '30s';
SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- 1. Reconcile agent.model_config_overrides to the curated codex set.
--    Runs BEFORE the upsert so the NOT IN set already contains the ids we are
--    about to (re)insert. Nothing legitimate is dropped: the list below IS
--    BridgeAllowlist.MODELS["codex"] after this change, and
--    BridgeProvidersHavePricingTest.codexReconciliationListsMatchTheAllowlist
--    fails the build if the two ever diverge.
-- ---------------------------------------------------------------------------

DELETE FROM model_config_overrides
 WHERE provider = 'codex'
   AND model_id NOT IN ('gpt-6-astra',
                        'gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5',
                        'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.4-nano',
                        'gpt-5.3-codex', 'gpt-5.2');

-- ---------------------------------------------------------------------------
-- 2. agent.model_config_overrides: catalog rows (picker) for the 2 missing ids.
-- ---------------------------------------------------------------------------

INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, bundle_version,
     price_input, price_output, last_synced_at, provider_kind)
VALUES
    ('codex', 'gpt-6-astra',  'GPT-6 Astra',  TRUE, 'curated', 1, 10,  50,   NOW(), 'bridge'),
    ('codex', 'gpt-5.4-nano', 'GPT-5.4 Nano', TRUE, 'curated', 1, 0.2, 1.25, NOW(), 'bridge')
ON CONFLICT (provider, model_id)
DO UPDATE SET enabled        = TRUE,
              display_name   = EXCLUDED.display_name,
              price_input    = EXCLUDED.price_input,
              price_output   = EXCLUDED.price_output,
              provider_kind  = 'bridge',
              source         = 'curated',
              last_synced_at = NOW();

-- ---------------------------------------------------------------------------
-- 3. auth.model_pricing: billing mirror (CreditService source of truth).
-- ---------------------------------------------------------------------------
-- 3a. Soft-close any codex pricing row that is no longer allow-listed.
UPDATE auth.model_pricing
   SET is_active    = false,
       effective_to = CURRENT_DATE
 WHERE provider = 'codex'
   AND is_active
   AND model NOT IN ('gpt-6-astra',
                     'gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna', 'gpt-5.5',
                     'gpt-5.4', 'gpt-5.4-mini', 'gpt-5.4-nano',
                     'gpt-5.3-codex', 'gpt-5.2');

-- 3b. Close any active row for the 2 ids from an earlier date, preserving the
--     one-active-row invariant (V336) before inserting today's row.
UPDATE auth.model_pricing
   SET is_active    = false,
       effective_to = CURRENT_DATE
 WHERE provider = 'codex'
   AND model IN ('gpt-6-astra', 'gpt-5.4-nano')
   AND is_active
   AND effective_from <> CURRENT_DATE;

INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    ('codex', 'gpt-6-astra',  10,  50,   0, CURRENT_DATE, true, 'bridge'),
    ('codex', 'gpt-5.4-nano', 0.2, 1.25, 0, CURRENT_DATE, true, 'bridge')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate    = EXCLUDED.input_rate,
              output_rate   = EXCLUDED.output_rate,
              fixed_cost    = 0,
              is_active     = true,
              provider_kind = 'bridge';
