-- V251 - PAYG one-time top-up infrastructure + CE default LLM source flag.
--
-- This single migration carries four related schema changes that ship together
-- because they are all behind the same feature gate (CE→Cloud LLM credits,
-- PR plan v8 approved 9.1/10 by three Opus auditors):
--
--   1. Widen the CHECK constraint on (price|subscription).cadence to allow
--      three new PAYG tiers - 'payg_small' ($10), 'payg_medium' ($50),
--      'payg_large' ($100). The legacy 'payg' cadence stays in the allow-list
--      so that existing rows (V4 seed) remain valid until we explicitly
--      disable them below.
--   2. Add a `disabled` boolean on both `plan` and `price`. The new column
--      lets us soft-disable rows that we no longer want to sell without
--      deleting them (Stripe references provider_price_id by uniqueness;
--      hard-deletes break history). All call sites that resolve a plan or
--      price by code must skip rows where disabled=TRUE - audited and fixed
--      in PR2 (CreditService, StripeBillingService, PriceCacheService).
--   3. Disable the legacy PAYG seed row: V4:38 created
--      (plan='PAYG', cadence='payg', provider_price_id=NULL) which was never
--      actually wired to Stripe - it was a placeholder. The new one-time
--      checkout flow (StripeBillingService.createPaygCheckoutSession in PR3)
--      uses mode=PAYMENT against the three new cadences instead. Insert the
--      three new price rows with provider_price_id=NULL - the Stripe Dashboard
--      step creates the actual prices manually, then an ops one-liner fills
--      provider_price_id. Until then a runtime startup health check
--      (CreditService boot, PR3) logs WARN + bumps payg_unconfigured_prices
--      Prometheus gauge.
--   4. Add ce_install_state.default_llm_source flag (VARCHAR(16) CHECK
--      ∈ {'CLOUD','BYOK'}). Drives the runtime decision in
--      LLMProviderFactory (PR6) between CloudLlmProvider (route via
--      /api/ce-llm-proxy/*) and the native BYOK provider. Default 'BYOK'
--      preserves existing CE deployments; the wizard /ce-setup (PR8) flips
--      to 'CLOUD' when the admin signs in with LiveContext. Cache invalidation
--      on flip is wired via LlmSourceChangedEvent + @TransactionalEventListener
--      (AFTER_COMMIT) in PR6.
--
-- Idempotent: all ALTER use IF EXISTS / IF NOT EXISTS where possible;
-- CHECK constraint drops are conditioned on PostgreSQL's auto-generated
-- name from V3 (`price_cadence_check`, `subscription_cadence_check`).

-- ===================================================================
-- 1. Widen cadence CHECK constraints on price + subscription
-- ===================================================================

ALTER TABLE auth.price
    DROP CONSTRAINT IF EXISTS price_cadence_check;

ALTER TABLE auth.price
    ADD CONSTRAINT price_cadence_check
    CHECK (cadence IN ('monthly', 'yearly', 'payg', 'payg_small', 'payg_medium', 'payg_large'));

ALTER TABLE auth.subscription
    DROP CONSTRAINT IF EXISTS subscription_cadence_check;

ALTER TABLE auth.subscription
    ADD CONSTRAINT subscription_cadence_check
    CHECK (cadence IN ('monthly', 'yearly', 'payg', 'payg_small', 'payg_medium', 'payg_large'));

-- ===================================================================
-- 2. Add `disabled` soft-deletion flag on plan + price
-- ===================================================================

ALTER TABLE auth.plan
    ADD COLUMN IF NOT EXISTS disabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth.price
    ADD COLUMN IF NOT EXISTS disabled BOOLEAN NOT NULL DEFAULT FALSE;

-- ===================================================================
-- 3. Disable legacy PAYG single-cadence row + seed 3 new tier rows
-- ===================================================================

-- Disable the V4:38 placeholder (cadence='payg', provider_price_id=NULL).
-- Re-runnable: WHERE clause matches only the legacy row; once disabled=TRUE,
-- subsequent runs UPDATE to the same value (no-op).
UPDATE auth.price
   SET disabled = TRUE
 WHERE plan_id = (SELECT id FROM auth.plan WHERE code = 'PAYG')
   AND cadence = 'payg'
   AND provider_price_id IS NULL;

-- Seed three new PAYG cadences. provider_price_id NULL = "not yet wired to
-- Stripe". The Dashboard step creates the actual price_xxx ids; ops UPDATE
-- fills the column. Until filled, StripeBillingService.createPaygCheckoutSession
-- throws IllegalStateException (PR3) which the startup health check warns about.
--
-- Idempotence guard: V3:96 declares UNIQUE (plan_id, cadence) on auth.price,
-- so a duplicate INSERT would raise SQLSTATE 23505 and abort the migration.
-- The NOT EXISTS guard short-circuits the INSERT before that, making the
-- migration safely re-runnable (e.g. after a partial-apply rollback).
INSERT INTO auth.price (plan_id, cadence, currency, amount_cents, provider, provider_price_id)
SELECT p.id, 'payg_small', 'usd', 1000, 'stripe', NULL
  FROM auth.plan p
 WHERE p.code = 'PAYG'
   AND NOT EXISTS (
       SELECT 1 FROM auth.price
        WHERE plan_id = p.id AND cadence = 'payg_small'
   );

INSERT INTO auth.price (plan_id, cadence, currency, amount_cents, provider, provider_price_id)
SELECT p.id, 'payg_medium', 'usd', 5000, 'stripe', NULL
  FROM auth.plan p
 WHERE p.code = 'PAYG'
   AND NOT EXISTS (
       SELECT 1 FROM auth.price
        WHERE plan_id = p.id AND cadence = 'payg_medium'
   );

INSERT INTO auth.price (plan_id, cadence, currency, amount_cents, provider, provider_price_id)
SELECT p.id, 'payg_large', 'usd', 10000, 'stripe', NULL
  FROM auth.plan p
 WHERE p.code = 'PAYG'
   AND NOT EXISTS (
       SELECT 1 FROM auth.price
        WHERE plan_id = p.id AND cadence = 'payg_large'
   );

-- ===================================================================
-- 4. Add ce_install_state.default_llm_source flag
-- ===================================================================

ALTER TABLE auth.ce_install_state
    ADD COLUMN IF NOT EXISTS default_llm_source VARCHAR(16) NOT NULL DEFAULT 'BYOK';

ALTER TABLE auth.ce_install_state
    DROP CONSTRAINT IF EXISTS ce_install_state_default_llm_source_check;

ALTER TABLE auth.ce_install_state
    ADD CONSTRAINT ce_install_state_default_llm_source_check
    CHECK (default_llm_source IN ('CLOUD', 'BYOK'));
