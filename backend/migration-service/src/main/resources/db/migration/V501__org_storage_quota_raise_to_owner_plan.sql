-- V501: raise every workspace whose storage allowance sits below what its owner's plan grants,
-- and pre-seed the entitled workspaces that have no quota row yet.
--
-- Why a THIRD pass at this
-- ------------------------
-- V205 created storage.organization_storage_quota with a 100 MB (FREE) row default. The
-- 2026-05-14 syncer fix taught PlanStorageQuotaSyncer to write the org row, and V266 repaired the
-- rows that existed that day. Both cover the same event: the owner's plan CHANGING. Neither
-- covers a workspace BORN after that change. Such a workspace has its row materialised lazily by
-- QuotaService.createDefaultOrganizationQuota, which cannot see the owner's plan and so writes
-- 100 MB, and nothing ever revisits it.
--
-- Production symptom (user 121): two workspaces, one owner, one plan, 100 GB on the workspace
-- that existed at the last plan change and 100 MB on the one created afterwards.
--
-- The code side of this fix seeds the owner's plan allowance when a workspace is created
-- (OrganizationService.seedNewWorkspaceStorageQuota), so workspace birth joins plan change as a
-- moment where the allowance is written. Neither covers every case on its own: a seed that fails
-- during a storage outage, or an owner who is past_due at creation time and so resolves to FREE,
-- is corrected at the owner's next plan change rather than immediately. This migration repairs
-- the installed base, which is where the reported damage already is.
--
-- This migration only ever RAISES
-- ------------------------------
-- It touches a row only when the plan grants strictly MORE than the row holds, and only for
-- workspaces whose owner is entitled to more than FREE. That is what makes it safe to run
-- unattended on a large table:
--   * it cannot shrink a workspace below its live usage, which would block uploads instantly for
--     a customer who did nothing wrong;
--   * it therefore needs no opinion on the cases where this SQL cannot see what the runtime sees.
--     A CE install governed by a linked cloud plan (CloudPlanAccess), a plan row whose
--     included_storage_bytes is NULL or 0, an allowance granted outside the plan: all of those
--     resolve to "no qualifying subscription" here, and all of them are simply left alone instead
--     of being reset to FREE. PlanStorageQuotaSyncer.isSyncRequired abstains on exactly the same
--     cases, so the two agree.
-- Downgrades stay the runtime syncer's job, on the plan-change event, where the billing context
-- is actually known.
--
-- Subscription statuses: 'active' and 'trialing' are the live ones, plus 'past_due' and
-- 'incomplete' so a temporary payment failure still grants the paid allowance (same choice as
-- V266 pass 1). Since the statement cannot lower anything, a generous status set costs nothing.
--
-- DISTINCT ON guards the (rare) multi-subscription case. It picks the MOST GENEROUS qualifying
-- plan first and only then the most recently touched row, which differs from the recency-only
-- heuristic of V198 / V266 on purpose: an owner holding both a real plan and a zero-storage one
-- (a CREDIT_PACK, say) must not be handed the zero-storage one just because it was touched last.
--
-- Idempotent: a second run writes nothing, so updated_at stays stable for workspaces already in
-- sync and no cache eviction storm follows a replay.

WITH entitled AS (
    -- Live workspaces whose owner's plan grants strictly more than the FREE allowance. Workspaces
    -- entitled to exactly FREE are excluded: their row is already correct, and where they have no
    -- row the lazy path produces the same number anyway.
    SELECT DISTINCT ON (org.id)
           org.id::text             AS org_id,
           p.included_storage_bytes AS storage_bytes
    FROM auth.organization org
    JOIN auth.billing_customer bc ON bc.user_id = org.owner_id
    JOIN auth.subscription s      ON s.billing_customer_id = bc.id
    JOIN auth.plan p              ON p.id = s.plan_id
    WHERE org.deleted_at IS NULL
      AND s.status IN ('active', 'trialing', 'past_due', 'incomplete')
      AND p.included_storage_bytes IS NOT NULL
      AND p.included_storage_bytes >
          COALESCE((SELECT included_storage_bytes FROM auth.plan WHERE code = 'FREE' LIMIT 1), 0)
    ORDER BY org.id,
             p.included_storage_bytes DESC,
             COALESCE(s.updated_at, s.created_at, TIMESTAMPTZ '1970-01-01') DESC
),
org_usage AS (
    -- Seeds used_bytes for the rows created below, with the same definition the application uses
    -- (StorageRepository.calculateOrganizationUsage), so a pre-seeded workspace does not
    -- under-report until its next reconciliation.
    SELECT st.organization_id, COALESCE(SUM(st.size_bytes), 0)::BIGINT AS used_bytes
    FROM storage.storage st
    JOIN entitled e ON e.org_id = st.organization_id
    WHERE st.status = 'ACTIVE'
    GROUP BY st.organization_id
)
INSERT INTO storage.organization_storage_quota
    (organization_id, max_bytes, used_bytes, soft_limit_bytes, hard_limit_bytes, created_at, updated_at)
SELECT e.org_id,
       e.storage_bytes,
       GREATEST(COALESCE(u.used_bytes, 0), 0),
       (e.storage_bytes * 0.8)::BIGINT,
       e.storage_bytes,
       NOW(),
       NOW()
FROM entitled e
LEFT JOIN org_usage u ON u.organization_id = e.org_id
ON CONFLICT (organization_id) DO UPDATE
-- The raise-only gate, applied per column with GREATEST rather than once on max_bytes. The three
-- limits are written together everywhere in the application, so in practice they move as one and
-- the plain assignment would do. GREATEST costs nothing and makes "this statement cannot lower a
-- value" true of a row in ANY state, including one where the columns disagree: gating on
-- max_bytes alone would let a row holding a high max_bytes and a stale hard_limit_bytes be
-- rewritten downward on the max. This runs once, unattended, against production, and cannot be
-- edited afterwards (Flyway checksums a migration on first apply), so the guarantee is worth the
-- three function calls.
--
-- used_bytes is deliberately absent: on an existing row it is live state owned by the usage
-- reconciliation, not by this repair.
SET max_bytes        = GREATEST(storage.organization_storage_quota.max_bytes, EXCLUDED.max_bytes),
    soft_limit_bytes = GREATEST(storage.organization_storage_quota.soft_limit_bytes, EXCLUDED.soft_limit_bytes),
    hard_limit_bytes = GREATEST(storage.organization_storage_quota.hard_limit_bytes, EXCLUDED.hard_limit_bytes),
    updated_at       = NOW()
WHERE storage.organization_storage_quota.max_bytes        < EXCLUDED.max_bytes
   OR storage.organization_storage_quota.soft_limit_bytes < EXCLUDED.soft_limit_bytes
   OR storage.organization_storage_quota.hard_limit_bytes < EXCLUDED.hard_limit_bytes;
