-- ============================================================================
-- V255 - undo speculative scaffolding from V251 + V252 + add V148 invariant
--         as a DB-level CHECK on the 2-bucket model.
--
-- Context: V251/V252 shipped foundation for a CE→Cloud LLM proxy feature whose
-- consumer (CloudLlmProvider) never landed. A retro-audit (round 5/5 of the
-- system-level scan) found:
--   - cloud_proxy_audit_log table created by V252 has ZERO writers and lives
--     in auth.* while the planned writer was in agent-service, violating the
--     per-service-schema rule (CLAUDE.md "Inter-Service Communication").
--   - auth.plan.disabled and auth.price.disabled columns added by V251 are
--     read by ZERO callers - every plan/price resolution query bypasses them.
--   - auth.ce_install_state.default_llm_source column added by V251 has no
--     HTTP endpoint that mutates it; the BYOK↔CLOUD flip mechanism the column
--     was designed to gate is gone with the proxy.
--   - The V148 invariant "delinquent ⇒ remaining_credits ≤ 0" was extended
--     in Javadoc to "(remaining + payg) ≤ 0" for V250's 2-bucket model, but
--     never enforced as a DB CHECK. A bug in service code that flipped
--     delinquent=TRUE with positive payg would pass silently.
--
-- Coherence + DRY (per CLAUDE.md "Pas de complexité au cas où"): drop the
-- dead scaffolding now so the audit-log table doesn't sit empty in prod and
-- the disabled flags don't become a "rotting silent toggle" surface. When
-- CloudLlmProvider ships, the foundation classes can be resurrected from
-- git history with the consumer in the SAME PR.
--
-- DROP IF EXISTS is used so this migration is safe on databases that ran
-- through V251 + V252 (drops the columns/table) AND on fresh databases that
-- skipped them via Flyway baseline (no-op).
-- ============================================================================

-- 1. Drop the dead audit-log table (had zero writers).
DROP TABLE IF EXISTS auth.cloud_proxy_audit_log;

-- 2. Drop the dead soft-disable flags (no readers, no consumer in PriceCacheService
--    or StripeBillingService - the V251 promise was never wired).
ALTER TABLE auth.plan  DROP COLUMN IF EXISTS disabled;
ALTER TABLE auth.price DROP COLUMN IF EXISTS disabled;

-- 3. Drop the dead BYOK/CLOUD source flag (no HTTP mutator, no listener
--    consumer after PR6 scaffolding was reverted).
ALTER TABLE auth.ce_install_state DROP COLUMN IF EXISTS default_llm_source;

-- 4. Enforce the V148 invariant at the DB level for the 2-bucket model.
--    The CHECK is named so future migrations can DROP/ADD it without scanning
--    the table for the predicate. Idempotent via DROP IF EXISTS first.
--
--    Defensive heal: a pre-V250 service bug could in theory have left a row
--    with delinquent=TRUE AND positive total balance. Adding the CHECK
--    without healing first would fail the migration and block deploy. The
--    UPDATE flips delinquent=FALSE on any such row - the credit substrate's
--    clearDelinquentIfPositive runtime path would do the same on the next
--    grant/refund, so this is just bringing the future deploy forward.
UPDATE auth.subscription
   SET delinquent = FALSE,
       updated_at = NOW()
 WHERE delinquent = TRUE
   AND (COALESCE(remaining_credits, 0) + COALESCE(payg_remaining_credits, 0)) > 0;

ALTER TABLE auth.subscription
    DROP CONSTRAINT IF EXISTS chk_subscription_delinquent_two_bucket;

ALTER TABLE auth.subscription
    ADD CONSTRAINT chk_subscription_delinquent_two_bucket
    CHECK (
        delinquent = FALSE
        OR (COALESCE(remaining_credits, 0) + COALESCE(payg_remaining_credits, 0)) <= 0
    );

COMMENT ON CONSTRAINT chk_subscription_delinquent_two_bucket ON auth.subscription IS
    'V148 invariant extended for V250 two-bucket model: delinquent=TRUE requires the SUM of both buckets to be ≤ 0. Fails-fast at INSERT/UPDATE time so service-code bugs that set delinquent=TRUE while one bucket is still positive surface immediately rather than silently - pre-V255 this invariant lived only in javadoc.';
