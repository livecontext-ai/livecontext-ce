-- V512: the Free plan's two monthly pots become ONE.
--
-- Until now a Free account held 1000 monthly credits that paid for workflow runs only,
-- plus a separate 100-credit AI allowance (V494) that paid for chat and agent turns on
-- the models opened to the free tier (V493). Two balances for one free plan read as a
-- bug to users ("I have 900 credits, why can't I chat?"), so the product now has one
-- pool: the 1000 monthly credits pay for workflow runs AND for chat/agent turns, the
-- latter still only on a model a cloud admin opened to the free tier
-- (auth.model_pricing.free_tier). The routing lives in CreditService.subBucketEligible;
-- this migration only retires the second pot.
--
--   1. plan.included_ai_credits -> NULL for FREE: no plan grants an AI allowance any
--      more, so renewal refills it to nothing (CreditAttributionService.refillAiAllowance)
--      and CreditService.aiAllowanceEligible refuses it on the plan check alone.
--   2. Existing Free subscriptions drop the allowance they still hold, so no wallet
--      surface keeps showing a balance the product no longer has. The pot sits outside
--      the balance CreditReconciliationService compares against the ledger (it adds back
--      ledger.ai_portion, which this does not touch), so zeroing it creates no drift.
--
-- The columns stay: ai_portion is history on existing ledger rows, and the per-plan
-- allowance remains a tunable an admin can set again with
--   UPDATE auth.plan SET included_ai_credits = N WHERE code = '<PLAN>';
--
-- CLOUD-ONLY in effect: CE runs credit.unlimited=true and never reads either column.
--
-- beforeEachMigrate resets search_path to orchestrator, so references MUST be
-- schema-qualified.

UPDATE auth.plan SET included_ai_credits = NULL WHERE code = 'FREE';

UPDATE auth.subscription s
SET ai_remaining_credits = 0
FROM auth.plan p
WHERE s.plan_id = p.id
  AND p.code = 'FREE'
  AND s.ai_remaining_credits <> 0;

COMMENT ON COLUMN auth.plan.included_ai_credits IS
    'Monthly AI allowance granted to this plan (V494). NULL = no AI pot. Since V512 no plan has one: the Free plan''s monthly credits fund workflows and free-tier chat/agent turns from a single pool. Tunable live.';

COMMENT ON COLUMN auth.subscription.ai_remaining_credits IS
    'Monthly AI allowance left (V494). Drawn by agent/chat/LLM debits on a free-tier model when the plan grants one; refilled to plan.included_ai_credits on renewal. 0 everywhere since V512, when the Free plan''s two pots were merged into remaining_credits.';
