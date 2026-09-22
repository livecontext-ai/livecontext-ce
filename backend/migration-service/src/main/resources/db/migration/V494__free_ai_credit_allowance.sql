-- V494: a separate monthly AI allowance, spendable only on free-tier models.
--
-- The Free plan already grants 1000 "normal" credits a month, and those stay
-- workflow-scoped (CreditService.WORKFLOW_SUB_ELIGIBLE_SOURCE_TYPES): an agent or
-- chat turn cannot draw them, so a fresh signup with no top-up could never run an
-- agent at all. This adds a SECOND, smaller pot that exists precisely for that.
--
--   subscription.ai_remaining_credits - the live balance of that pot.
--   plan.included_ai_credits          - how much it is refilled to each month.
--                                       NULL = the plan has no AI pot (paid plans:
--                                       their normal wallet already funds agents,
--                                       so nothing about them changes).
--
-- Deliberately a THIRD bucket rather than a bigger grant: the point is that a costly
-- agent run can never eat the workflow credits, and that the platform's exposure to
-- free AI usage is one number an admin can move. Per account and per month that
-- exposure is included_ai_credits PLUS at most one turn's post-flight overshoot: a
-- chat turn that straddles the end of the pot has its uncovered tail absorbed rather
-- than booked as debt (CreditService.applyDebit), and the pot is empty afterwards, so
-- the next turn is refused up front. Tunable live, same convention as the V58 per-plan
-- resource limits:
--   UPDATE auth.plan SET included_ai_credits = 250 WHERE code = 'FREE';
--
-- Spending it ALSO requires the model to be opened to the free tier
-- (agent.model_config_overrides.free_tier_enabled, V493). Allowance and model list
-- are two independent gates and both must pass.
--
-- CLOUD-ONLY: a CE install runs credit.unlimited=true, where every consume path
-- short-circuits before bucket routing, so the pot is never read there.
--
-- beforeEachMigrate resets search_path to orchestrator, so references MUST be
-- schema-qualified.

ALTER TABLE auth.subscription
    ADD COLUMN IF NOT EXISTS ai_remaining_credits NUMERIC(12, 4) NOT NULL DEFAULT 0;

-- The floor is enforced in CreditService.applyDebit (the pot is spent to the last
-- credit and the remainder falls through to sub/PAYG), but unlike those two buckets
-- this one has NO legitimate negative state - it is an entitlement, not a wallet that
-- can carry debt. Assert it in the schema too, same convention as the V255/V379
-- delinquency CHECK: a bug that drove it negative would otherwise hand out free
-- inference and only surface as a reconciliation oddity weeks later.
ALTER TABLE auth.subscription
    DROP CONSTRAINT IF EXISTS chk_subscription_ai_credits_non_negative;
ALTER TABLE auth.subscription
    ADD CONSTRAINT chk_subscription_ai_credits_non_negative
    CHECK (ai_remaining_credits >= 0);

COMMENT ON COLUMN auth.subscription.ai_remaining_credits IS
    'Monthly AI allowance left. Drawn by agent/chat/LLM debits that run on a free-tier model; refilled to plan.included_ai_credits on renewal. Separate from remaining_credits (workflow) and payg_remaining_credits (top-ups).';

-- How much of a debit the allowance paid. That part never left the wallet
-- CreditReconciliationService compares the ledger against (remaining_credits +
-- payg_remaining_credits), so without this column every AI-funded turn would grow an
-- unexplained drift of exactly its cost and page ops. Mirrors payg_portion, which
-- exists for the same reason on the reservation path.
--
-- `amount` is what the buckets ACTUALLY gave up, which is the full cost on every path
-- but one: a free-tier turn whose cost outruns the pot has its uncovered tail absorbed
-- rather than booked as PAYG debt (CreditService.applyDebit - a negative PAYG on a
-- workflow-credits-only plan is unclearable, so creating one bricks the account). The
-- ledger states the movement; the absorbed part is platform cost and appears nowhere.
ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS ai_portion NUMERIC(15, 4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN auth.credit_ledger.ai_portion IS
    'Part of this debit funded by the monthly AI allowance (V494). Zero on every non-AI row. Added back by CreditReconciliationService, since the allowance sits outside the reconciled balance.';

ALTER TABLE auth.plan
    ADD COLUMN IF NOT EXISTS included_ai_credits INTEGER;

COMMENT ON COLUMN auth.plan.included_ai_credits IS
    'Monthly AI allowance granted to this plan. NULL = no AI pot (paid plans fund agents from the normal wallet). Tunable live.';

-- Free starts at 100. Paid plans stay NULL on purpose: giving them a pot would
-- route their agent spend away from the wallet they already pay for.
UPDATE auth.plan SET included_ai_credits = 100 WHERE code = 'FREE';

-- Existing Free subscribers get their first allowance immediately rather than
-- waiting for the next monthly renewal - otherwise the feature reads as broken
-- for every account that already exists on the day it ships.
--
-- VERIFIED ACCOUNTS ONLY, matching the code path that grants it from here on
-- (CreditAttributionService.attributeOnSubscription, reached only through
-- UserResolutionService.attributeCreditsIfEligible, which returns early for an
-- unverified email). The pot buys real platform-key inference, so handing it to
-- every unverified row sitting in the table would be the same giveaway the code
-- gate exists to prevent, just applied retroactively. An unverified account picks
-- it up the moment it verifies.
--
-- The '= 0' guard keeps a re-run from topping up a pot the account has spent.
UPDATE auth.subscription s
SET ai_remaining_credits = p.included_ai_credits
FROM auth.plan p, auth.billing_customer bc, auth.users u
WHERE s.plan_id = p.id
  AND s.billing_customer_id = bc.id
  AND bc.user_id = u.id
  AND u.email_verified = TRUE
  AND s.status IN ('active', 'trialing')
  AND p.included_ai_credits IS NOT NULL
  AND s.ai_remaining_credits = 0;
