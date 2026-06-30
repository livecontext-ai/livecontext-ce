-- V224 - Credits PROFOND: subscription.organization_id + credit_ledger.organization_id
--
-- Context: the multi-org refactor (PR15→PR30, V202/V209/V210/V218) shipped
-- workspace tags on every user-owned resource EXCEPT the billing/credit
-- substrate. Today `auth.subscription` is keyed on `billing_customer_id ->
-- user_id` (per-user, no org dim) and `auth.credit_ledger` only carries
-- `user_id` (payer) + `executor_user_id` (PR11) - no workspace tag.
--
-- The PR8 Q1=b redirect (CreditService.resolvePayer) papered over this by
-- redirecting a member's consumption to the org owner's user-subscription.
-- That works for billing (owner's wallet pays) but conflates the contracts:
--
--   • If the same user owns both a personal subscription AND a TEAM
--     subscription, the platform cannot represent that - they have ONE
--     subscription row keyed on user_id. Upgrading to TEAM clobbers their
--     personal scope's credit balance, which is the "credits stuck at 1000
--     after FREE→TEAM upgrade" prod symptom users report.
--
--   • Ledger queries cannot answer "how many credits did this org consume?"
--     without joining through organization_member + organization.owner_id +
--     filtering on the redirect heuristic. That's the source of the "usage
--     history empty in /app/agent for org workspace" bug.
--
--   • The redirect short-circuits to executor on null-default-org / soft-
--     deleted org. (ADR-009 rollback: column dormant - owner-pays routing
--     resolves the payer via PlanResolutionService and reads the owner's
--     wallet via findActiveByUserId, no longer keyed on organization_id.)
--
-- This migration adds the workspace tag at the source. Going forward:
--
--   1. `subscription.organization_id IS NULL` → personal-user subscription
--      (legacy behaviour preserved).
--   2. `subscription.organization_id = :orgId` → an org's subscription.
--      `subscription.billing_customer_id -> user_id` is the BILLING OWNER
--      (= org owner at create time; survives org transfer). All members of
--      that org consume from this subscription's `remaining_credits` pool.
--   3. `credit_ledger.organization_id` is set when the consumption happened
--      from an org workspace. Indexed for "show me my org's credit history"
--      reads from the new bell/dashboard surfaces.
--
-- Unique-active-per-org: a non-null `organization_id` collides only with
-- another active subscription in the SAME org. Personal subs (NULL org_id)
-- are unrestricted (one user can have one personal sub + own N org subs).
--
-- Backfill: NULL on every existing row. Legacy code paths reading
-- `findActiveByUserId(...)` continue to return the (NULL-org_id) personal
-- subscription. Org-scope readers added in this PR look up via the new
-- `findActiveByOrganizationId(orgId)` finder and get null until a real
-- org subscription is provisioned via Stripe checkout in an org workspace
-- context.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS.

-- 1. subscription.organization_id

ALTER TABLE auth.subscription
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(64);

COMMENT ON COLUMN auth.subscription.organization_id IS
    'V224 - workspace tag for the org-aware subscription model. NULL = personal '
    'subscription (legacy behaviour, billing_customer_id -> user_id is the owner '
    'AND consumer). Non-null = org subscription (billing_customer_id -> user_id '
    'is the billing OWNER; all members of organization_id consume from this row).';

-- 2. credit_ledger.organization_id

ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(64);

COMMENT ON COLUMN auth.credit_ledger.organization_id IS
    'V224 - workspace tag for org-scope ledger reads. NULL = personal-scope '
    'debit (executor was in their personal workspace OR the row predates V224 '
    'backfill - both treated as personal scope by org-scope readers). Non-null '
    '= consumption originated from an org workspace; routed through the org''s '
    'subscription. {user_id = billing owner, executor_user_id = caller, '
    'organization_id = workspace tag} - the three identities are distinct.';

-- Indexes for the new columns moved to V225 with CREATE INDEX CONCURRENTLY
-- + `flyway:transactional=false` directive. The audit (round-2 Opus) flagged
-- non-CONCURRENT index creation on auth.credit_ledger as a prod-locking
-- hazard - the table is HOT and large enough that ACCESS EXCLUSIVE during
-- index build would freeze writes. Keeping the V224 step column-only is
-- safe: the only risk vector this migration leaves open is the brief
-- window between V224 (column added) and V225 (indexes built) where
-- partial-index-backed queries would seq-scan - acceptable for the
-- minutes-long migration window.
