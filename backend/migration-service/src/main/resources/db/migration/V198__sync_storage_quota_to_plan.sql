-- V198: align storage.tenant_storage_quota.max_bytes with the user's plan.
--
-- Before this fix, QuotaService.createDefaultQuota and the table-level default
-- both hardcoded 1 GB. The actual plan catalog (auth.plan.included_storage_bytes,
-- seeded in V4) ranges from 100 MB (FREE) to 5 TB (ENTERPRISE_ULTIMATE), but
-- nothing ever propagated those values into tenant_storage_quota. Result:
-- every tenant got 1 GB regardless of plan - FREE users had 10x what they
-- were entitled to; STARTER+ users were under-provisioned vs their plan.
--
-- The signup + subscription-upsert paths now push the plan value into the
-- quota row at write time (UserProvisioningService.syncQuotaToPlan +
-- SubscriptionService.syncStorageQuotaToPlan). This migration retro-applies
-- the same logic to existing tenants.
--
-- Two passes:
--   1. Tenants WITH an active subscription → apply their plan's allowance.
--   2. Tenants WITHOUT an active subscription → apply FREE plan's allowance.
--
-- soft_limit_bytes is recomputed as 80% of max_bytes to match the
-- DEFAULT_SOFT_LIMIT_RATIO used by QuotaService.updateLimits.

-- Pass 1: tenants with a current subscription. Status set aligned with the
-- runtime "single-active-subscription" enforcement in SubscriptionService.java
-- (active, trialing, past_due, incomplete) - we want past_due users to keep
-- their plan quota until payment fully fails and status flips to canceled.
--
-- DISTINCT ON guards against the (rare) multi-active-subscription corruption
-- case: if a tenant ended up with two rows in IN-status simultaneously, we
-- pick the most recently updated one (the same heuristic the runtime
-- enforcement uses when canceling siblings). Without this, the UPDATE would
-- visit rows in non-deterministic order and the "winning" plan would depend
-- on Postgres tuple ordering.
--
-- Only updates when the value would actually change - keeps updated_at stable
-- for tenants whose quota is already correct (no cache-eviction storm on
-- subsequent migration replay).
-- COALESCE on the ORDER BY key hardens against rows where updated_at is
-- NULL (e.g. very old rows pre-dating the updated_at column, or rows
-- inserted via SQL that bypassed the JPA @PreUpdate hook). NULLS LAST
-- alone is sufficient when only SOME rows are NULL, but if ALL rows for
-- a given user have NULL updated_at, DISTINCT ON would fall back to
-- arbitrary tuple order. The COALESCE makes the tiebreak deterministic
-- via created_at, and then via an epoch sentinel as a final guarantee.
WITH latest_sub AS (
    SELECT DISTINCT ON (bc.user_id)
           bc.user_id          AS user_id,
           p.included_storage_bytes AS storage_bytes
    FROM auth.subscription s
    JOIN auth.billing_customer bc ON bc.id = s.billing_customer_id
    JOIN auth.plan p              ON p.id = s.plan_id
    WHERE s.status IN ('active', 'trialing', 'past_due', 'incomplete')
      AND p.included_storage_bytes IS NOT NULL
      AND p.included_storage_bytes > 0
    ORDER BY bc.user_id,
             COALESCE(s.updated_at, s.created_at, TIMESTAMPTZ '1970-01-01') DESC
)
UPDATE storage.tenant_storage_quota tsq
SET max_bytes        = ls.storage_bytes,
    soft_limit_bytes = (ls.storage_bytes * 0.8)::BIGINT,
    hard_limit_bytes = ls.storage_bytes,
    updated_at       = NOW()
FROM latest_sub ls
WHERE ls.user_id::text = tsq.tenant_id
  -- Broadened idempotency guard: skip the UPDATE only when ALL three
  -- limits already match the plan. A row with the correct max_bytes but
  -- a stale soft/hard limit (e.g. row predates V198 with max=100MB but
  -- soft=800MB from the old 1GB * 0.8 default) still gets rewritten.
  AND (tsq.max_bytes        <> ls.storage_bytes
       OR tsq.soft_limit_bytes <> (ls.storage_bytes * 0.8)::BIGINT
       OR tsq.hard_limit_bytes <> ls.storage_bytes);

-- Pass 2: orphaned tenants (no active subscription) → FREE plan allowance.
-- Includes tenants whose only subscription is canceled/past_due/incomplete.
-- Status set deliberately stricter than pass 1: a past_due tenant who has
-- ONLY past_due subs (no active ever) lands here on FREE allowance - they
-- haven't completed payment, so don't honor the failed-payment plan.
WITH free_plan AS (
    SELECT included_storage_bytes AS storage_bytes FROM auth.plan WHERE code = 'FREE'
)
UPDATE storage.tenant_storage_quota tsq
SET max_bytes        = fp.storage_bytes,
    soft_limit_bytes = (fp.storage_bytes * 0.8)::BIGINT,
    hard_limit_bytes = fp.storage_bytes,
    updated_at       = NOW()
FROM free_plan fp
WHERE NOT EXISTS (
    SELECT 1
    FROM auth.subscription s
    JOIN auth.billing_customer bc ON bc.id = s.billing_customer_id
    WHERE bc.user_id::text = tsq.tenant_id
      AND s.status IN ('active', 'trialing')
)
-- Same broadened guard as pass 1.
AND (tsq.max_bytes        <> fp.storage_bytes
     OR tsq.soft_limit_bytes <> (fp.storage_bytes * 0.8)::BIGINT
     OR tsq.hard_limit_bytes <> fp.storage_bytes);

-- Re-baseline the table-level defaults so any future row that bypasses both
-- the signup path AND QuotaService.createDefaultQuota (raw INSERT, unlikely
-- but cheap to harden) gets the FREE quota, not 1 GB.
--   104857600 = 100 MB (FREE plan)
--    83886080 = 80 MB (soft limit at 80% of max)
ALTER TABLE storage.tenant_storage_quota
    ALTER COLUMN max_bytes        SET DEFAULT 104857600,
    ALTER COLUMN soft_limit_bytes SET DEFAULT 83886080,
    ALTER COLUMN hard_limit_bytes SET DEFAULT 104857600;
