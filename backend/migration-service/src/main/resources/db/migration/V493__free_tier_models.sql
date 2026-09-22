-- V493: mark which cloud models a FREE-plan account may run with its AI allowance.
--
-- The FREE monthly grant funds WORKFLOW_NODE only (CreditService's
-- WORKFLOW_SUB_ELIGIBLE_SOURCE_TYPES): chat and agent turns drew the PAYG bucket
-- alone, which is 0 for a fresh signup. A visitor could therefore never run an
-- agent without topping up first. V494 answers that with a SEPARATE 100-credit AI
-- allowance; these two columns are what lets a cloud admin choose which models
-- that allowance may be spent on. The monthly grant is NOT widened by this flag:
-- it stays workflow-only whatever the flag says.
--
-- Two columns because the catalog and the ledger live in different schemas and a
-- service may only query its own (see AGENTS.md "Inter-Service Communication"):
--   agent.model_config_overrides.free_tier_enabled - the SOURCE OF TRUTH, edited
--     by a cloud admin in the Models panel.
--   auth.model_pricing.free_tier - the billing MIRROR, pushed by
--     AuthPricingSyncClient on the same path that already carries provider_kind.
--     CreditService reads it to decide whether the AI ALLOWANCE may fund a turn.
-- A stale mirror fails CLOSED (free_tier = FALSE = the allowance does not fund the
-- turn), which is the safe direction: the user is asked to top up, never billed by
-- surprise.
--
-- Scope coupling worth knowing before adding a model category: the allowance only
-- funds the source types in CreditService.AI_ALLOWANCE_SOURCE_TYPES (agent, chat,
-- classify, guardrail, compaction, browser agent, CLI session). The admin panel
-- surfaces this flag on its two tabs, chat and browser_agent, and both are in that
-- set; image-generation rows are mode-filtered out of both and can never carry it.
-- A future tab whose source type is NOT in that set would put a switch on screen
-- that changes nothing.
--
-- CLOUD-ONLY, like bundle_enabled (V381): a CE install has no credits, no plans
-- and cloud-multiplier = 1.0, so the flag is never serialized into the catalog
-- bundle and never read CE-side.
--
-- ROLLOUT: this migration opens NOTHING. Both columns default to FALSE, and there is
-- deliberately no seed - which models are worth grant-funded inference is an admin's
-- call, not a migration's. The pricing page, the plan cards and the comparison table
-- announce the allowance unconditionally, so until an admin opens at least one model
-- the product advertises credits that cannot buy anything and a Free account is
-- refused on its first turn. The Models panel says so in an amber notice while no
-- model is open (aiProviders.modelConfig.freeTierNoneOpen); opening one is the last
-- step of shipping this feature.
--
-- Both tables live outside the orchestrator schema and beforeEachMigrate resets
-- search_path to orchestrator, so every reference MUST be schema-qualified.

ALTER TABLE agent.model_config_overrides
    ADD COLUMN IF NOT EXISTS free_tier_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN agent.model_config_overrides.free_tier_enabled IS
    'Cloud-only: TRUE = a FREE-plan account may spend its monthly AI allowance (auth.subscription.ai_remaining_credits, V494) on this model. Does NOT widen the monthly credit grant, which stays workflow-only. Never shipped in the CE catalog bundle.';

ALTER TABLE auth.model_pricing
    ADD COLUMN IF NOT EXISTS free_tier BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN auth.model_pricing.free_tier IS
    'Billing mirror of agent.model_config_overrides.free_tier_enabled, pushed by AuthPricingSyncClient. Read by CreditService to decide whether the monthly AI allowance (ai_remaining_credits, V494) may fund an LLM debit; it never routes to the sub bucket. Fails closed (FALSE) when the mirror is stale.';
