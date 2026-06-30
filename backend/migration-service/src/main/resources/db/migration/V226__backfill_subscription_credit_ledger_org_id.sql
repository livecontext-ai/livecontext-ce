-- V226 - backfill auth.subscription.organization_id + auth.credit_ledger.organization_id
--
-- Context: V224 added `organization_id` to both tables but did NOT backfill
-- existing rows. They sit at NULL. Post-deploy the frontend sends
-- `X-Active-Organization-ID = <user's default org id>` (every user has one
-- via V202 backfill default - either their personal org or a TEAM they're
-- a member of). CreditController/Service routes the read through
-- `findActiveByOrganizationId(orgId)` (strict-org), which returns empty
-- because subscription.organization_id IS NULL ≠ the personal/team org id.
--
-- Visible production symptoms reported by the user post-deploy:
--   • /credits/balance returns 0 even when remaining_credits > 0
--   • /credits/history returns empty
--   • /credits/summary returns empty
--   • /credits/analytics returns empty
--
-- (Conversations / interfaces / publications worked correctly because
-- V211/V202/V218 had backfilled their org_id from the creator's default
-- membership - V224 missed the same cascade for the new two columns.)
--
-- Fix shape: for each NULL row, look up the row's owning user's default
-- organization_member and copy that org_id. The lookup is:
--   subscription.billing_customer_id -> auth.billing_customer.user_id
--   credit_ledger.user_id (already the payer's user id)
-- Both then join `auth.organization_member WHERE is_default` to get the
-- canonical workspace tag.
--
-- Idempotent: WHERE organization_id IS NULL on both UPDATEs. Safe to
-- re-run; rows already backfilled in a previous attempt are skipped.
--
-- Forward-compat: SubscriptionService is updated in the same PR to populate
-- organization_id on every new subscription create (Stripe webhook + internal
-- create path), so V226 only repairs historical data - going forward the
-- NULL state should not recur.
--
-- Edge cases preserved as NULL:
--   • User has no default organization_member row (degenerate state, rare).
--     Their subscription / ledger row stays NULL - strict-org reads still
--     miss but no data corruption. The legacy `findActiveByUserId` fallback
--     in CreditService.resolveActiveSubscription handles this safety net.
--   • Soft-deleted orgs: the SELECT joins `auth.organization` and filters
--     `deleted_at IS NULL` so a tomb-stoned org is not picked up.

-- 1. auth.subscription - match to billing customer's user's default org.
--    AUDIT FIX: limit to PERSONAL orgs only. The V225 UNIQUE partial index
--    `idx_subscription_active_per_org WHERE status='active'` would COLLIDE
--    if multiple users share a TEAM org as their default (legal per
--    `OrganizationController.setDefault`). Personal orgs are 1:1 with users
--    (UNIQUE owner_id + is_personal=true), so backfilling only personal
--    targets is collision-free by construction. TEAM-org subscriptions are
--    a separate flow (Stripe checkout in org context) that the
--    SubscriptionService update populates explicitly post-deploy.
UPDATE auth.subscription s
   SET organization_id = sub.org_id_text
  FROM (
    SELECT bc.id AS billing_customer_id,
           m.organization_id::text AS org_id_text
      FROM auth.billing_customer bc
      JOIN auth.organization_member m ON m.user_id = bc.user_id AND m.is_default = TRUE
      JOIN auth.organization o ON o.id = m.organization_id
                              AND o.deleted_at IS NULL
                              AND o.is_personal = TRUE
  ) sub
 WHERE s.billing_customer_id = sub.billing_customer_id
   AND s.organization_id IS NULL;

-- 2. auth.credit_ledger - match to row's user_id's default org
UPDATE auth.credit_ledger cl
   SET organization_id = lookup.org_id_text
  FROM (
    SELECT m.user_id,
           m.organization_id::text AS org_id_text
      FROM auth.organization_member m
      JOIN auth.organization o ON o.id = m.organization_id AND o.deleted_at IS NULL
     WHERE m.is_default = TRUE
  ) lookup
 WHERE cl.user_id = lookup.user_id
   AND cl.organization_id IS NULL;
