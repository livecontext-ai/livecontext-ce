-- ============================================================================
-- V379 : extend the V255 delinquent CHECK for Free workflow-credit scoping.
--
-- Context: on the FREE plan the monthly (sub) bucket funds ONLY workflow-node
-- orchestration; chat/agent turns draw the PAYG bucket alone. A chat post-flight
-- overshoot (allowNegative debit) therefore drives payg_remaining_credits
-- NEGATIVE while the untouched monthly grant keeps the two-bucket TOTAL
-- positive. That debt is real - the sub bucket can never repay it - but the
-- V255 CHECK (delinquent ⇒ total ≤ 0) made it ILLEGAL to flag the account
-- delinquent, so the delinquency gate (tryReserveMarkup fresh-reserve refusal)
-- never fired for Free accounts and the overshoot was repeatable without bound.
--
-- CreditService now sets delinquent=TRUE when a non-sub-eligible debit leaves
-- the PAYG bucket negative (only reachable on FREE), and
-- clearDelinquentIfPositive keeps a FREE account delinquent while its PAYG
-- bucket is negative (paid plans keep the pure total-based clear: every paid
-- debit nets against the total, so a positive total means the debt is
-- recovered). The CHECK cannot express the plan (other table), so it admits
-- the payg-negative state for any row; service code only creates it on FREE.
-- This migration relaxes the CHECK to match:
--
--   delinquent = TRUE  ⇒  (total ≤ 0)  OR  (payg_remaining_credits < 0)
--
-- Pure relaxation of V255's predicate (adds an OR branch): every row valid
-- under the old CHECK stays valid, so no heal UPDATE is needed and the
-- migration cannot fail on existing data. Idempotent via DROP IF EXISTS.
-- ============================================================================

ALTER TABLE auth.subscription
    DROP CONSTRAINT IF EXISTS chk_subscription_delinquent_two_bucket;

ALTER TABLE auth.subscription
    ADD CONSTRAINT chk_subscription_delinquent_two_bucket
    CHECK (
        delinquent = FALSE
        OR (COALESCE(remaining_credits, 0) + COALESCE(payg_remaining_credits, 0)) <= 0
        OR COALESCE(payg_remaining_credits, 0) < 0
    );

COMMENT ON CONSTRAINT chk_subscription_delinquent_two_bucket ON auth.subscription IS
    'V148 invariant, V250 two-bucket, extended V379 for Free workflow-credit scoping: delinquent=TRUE requires the SUM of both buckets to be <= 0 OR a negative PAYG bucket (Free chat/agent overshoot debt that the monthly workflow-only grant can never repay). Fails-fast at INSERT/UPDATE so service-code bugs surface immediately.';
