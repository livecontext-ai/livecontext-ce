-- Seed TypeSafe's Jev, the first DECISION model in the catalog: it returns a typed
-- choice with a probability per option instead of text.
--
-- WHY A MIGRATION AND NOT THE SYNC. Neither feed carries it. LiteLLM has no entry,
-- and the OpenRouter feed is rejected for this provider anyway. Provider-endpoint
-- discovery cannot help either: TypeSafe has no /models endpoint of the shape the
-- discovery expects, and Jev ships as a single alias. One curated row is the whole
-- catalog for this provider.
--
-- MODE = 'decision', AND IT IS LOAD-BEARING. ModelCategory.acceptsMode admits chat and
-- browser_agent only for mode IS NULL OR mode = 'chat', and ModelCatalogService resolves
-- the category-less global path (the chat picker, the flat model list, the default-model
-- pick) as 'chat'. Writing 'chat' here, or leaving it NULL, would offer Jev in the chat
-- picker, where every selection breaks.
--
-- THIS ROW IS ONLY HALF OF THAT. The catalog is assembled from these rows AND from the
-- YAML-declared models, and the filter reads a mode off both. A YAML model with no mode is
-- read as chat-eligible, and dropping this row from the overlay does not remove it - so
-- the mode must ALSO be declared by the provider bean (TypeSafeDecisionProvider
-- .getModelMode, from LLMProvider.MODE_DECISION, which is the single spelling this value
-- copies). Change one without the other and the model reappears in the chat picker.
--
-- PRICES: USD per 1M tokens, TypeSafe's published list. Output is free and is stored as
-- 0.0000 rather than NULL: NULL means "unknown" to the billing path and would make
-- ModelPricingService fall back to its default rates. A real zero is a real price here.
-- The trigger derive_model_credits() fills credits_input / credits_output.
--
-- NO CONTEXT WINDOW / MAX OUTPUT. TypeSafe publishes neither, and guessing would put a
-- fabricated number into the budget guards' worst-case bound. NULL is the documented
-- "unknown" and the guards fall back to growth projection, which is what every model
-- seeded before V162 already does. Safe here because the flag that turns a NULL window
-- into a refusal (BUDGET_GUARD_REQUIRE_CTX_WINDOW) is off: GuardChainFactory builds
-- TenantBudgetGuard through its two-argument constructor.
--
-- CAPABILITIES ARE ALL FALSE, and not by omission: there is no tool calling, no vision,
-- no reasoning trace and no prompt cache, because there is no text channel in which any
-- of them could be expressed.
--
-- FREE TIER stays FALSE (the column default). Whether a FREE-plan account may fund a
-- classification on this model from its AI allowance is a pricing decision, and a
-- migration should not assume it.
--
-- AND THIS ROW IS NOT REACHABLE FROM THE ADMIN PANEL. That panel lists the chat and
-- browser_agent categories only, and both of its reads apply the chat mode filter, so a
-- mode='decision' row is invisible there: no enable toggle, no ranking, no price edit, no
-- free-tier switch. Turning this model off or repricing it today means the environment
-- key or SQL. A classification tab is the missing piece; until it exists, say so here
-- rather than let a reader go looking for a screen that does not exist.
--
-- IDEMPOTENT: DO NOTHING on the catalog, DO UPDATE on the billing mirror, and the
-- category insert is guarded on (model_config_id, category).
--
-- ORDERING HAZARD WORTH KNOWING. This row is not marked is_custom, and a signed catalog
-- bundle applies with deprecateMissing=true. So a bundle BUILT BEFORE this migration
-- reached the cloud would stamp deprecated_at on it, and a bundle carrying `typesafe`
-- without a mode would null the decision mode and put the model straight back into the
-- chat picker - the exact failure the MODE paragraph above exists to prevent. Rebuild and
-- activate the cloud bundle after this migration lands, before the next CE sync. The SEED
-- path is unaffected (it merges with deprecateMissing=false).

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- Catalog row
-- ---------------------------------------------------------------------------
INSERT INTO model_config_overrides
    (provider, model_id, display_name, enabled, source, provider_kind, mode, tier, ranking,
     price_input, price_output, price_floor_input, price_floor_output,
     context_window, max_output_tokens,
     supports_tools, supports_vision, supports_reasoning, supports_prompt_caching,
     last_synced_at, feed_metadata)
VALUES
    -- enabled=TRUE, not NULL: NULL means "no explicit decision" and reads as enabled
    -- everywhere, but it leaves the admin panel's toggle in an indeterminate state for a
    -- row the platform is deliberately shipping on. V157 writes TRUE for the same kind of
    -- curated manual row.
    ('typesafe', 'jev-latest', 'Jev', TRUE, 'manual', 'byok', 'decision', 'budget', NULL,
     0.0420, 0.0000, 0.0420, 0.0000, NULL, NULL,
     FALSE, FALSE, FALSE, FALSE, NOW(),
     '{"priceFrom":"typesafe","note":"System One decision model; output billed at zero by the vendor"}'::jsonb)
ON CONFLICT (provider, model_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Category sidecar. NOT what decides eligibility: that is the mode column above, and a
-- row with no sidecar entry passes through applyCategoryOverlay unchanged. What this row
-- buys is addressability - a per-category enabled flag and rank that an admin surface can
-- write - so the model is consistent with every other catalogue row rather than a special
-- case the day one is built.
--
-- ONE category, and deliberately not chat: 'classification' is the only one whose
-- acceptsMode admits mode='decision'. rank is NULL because ranking orders rivals and
-- this category has a single member.
-- ---------------------------------------------------------------------------
INSERT INTO model_category_settings (model_config_id, category, enabled, rank)
SELECT m.id, 'classification', TRUE, NULL
FROM model_config_overrides m
WHERE m.provider = 'typesafe' AND m.model_id = 'jev-latest'
ON CONFLICT (model_config_id, category) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Billing mirror (auth.model_pricing is the table the billing path reads).
--
-- THIS ROW IS THE POINT OF THE MIGRATION. ModelPricingService.getPricing falls back to
-- DEFAULT_INPUT_RATE 1.0 / DEFAULT_OUTPUT_RATE 4.0 USD per 1M when a (provider, model)
-- has no row, logging a warning and nothing else. Shipping the catalog row without this
-- one would bill every classification at 24x the real input rate AND charge for output
-- tokens the vendor gives away, silently, for as long as nobody read the log.
-- ---------------------------------------------------------------------------
-- PROVIDER_KIND IS WRITTEN EXPLICITLY, and it is 'byok' rather than 'cloud'. The column
-- comment (V117) defines the values as how the provider is CONSUMED: cloud = via the
-- LiveContext proxy, byok = an admin API key, bridge = a CLI. TypeSafe is an admin API
-- key, and the cloud relay cannot execute it at all (it speaks chat-completions, not
-- System One), so 'cloud' would be false. Noting the tension because V141 wrote 'cloud'
-- for the image-generation rows, which are also platform-paid but likewise keyed by an
-- admin key: if cost accounting ever needs "who pays" rather than "how it is consumed",
-- that is a separate column, and both sets of rows want changing together.
INSERT INTO auth.model_pricing
    (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES
    ('typesafe', 'jev-latest', 0.0420, 0.0000, 0, CURRENT_DATE, true, 'byok')
ON CONFLICT (provider, model, effective_from)
DO UPDATE SET input_rate  = EXCLUDED.input_rate,
              output_rate = EXCLUDED.output_rate,
              is_active   = true;
