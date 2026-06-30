-- V266: align storage.organization_storage_quota.max_bytes with the org
-- owner's active subscription plan. Mirror of V198 for the org-scope quota
-- table that landed in V205.
--
-- Why this is needed
-- ------------------
-- V205 created storage.organization_storage_quota with a row-level default
-- of 100 MB (FREE) and a comment promising the live value would be sourced
-- from the org owner's active subscription via PlanStorageQuotaSyncer. The
-- syncer was fixed (2026-05-14) to also write the org row when an owner's
-- plan changes - but it only fires on the upsert path. Orgs whose owner
-- never changed plan AFTER the syncer fix landed are stuck at the 100 MB
-- table default, regardless of the owner being on TEAM / PRO / ENTERPRISE.
-- User-visible symptom: "Storage 58.2 MB of 100.0 MB" on a TEAM workspace.
--
-- Two passes, mirroring V198
-- --------------------------
--   Pass 1: orgs whose owner has an active subscription → apply that plan's
--           included_storage_bytes.
--   Pass 2: orgs whose owner has NO active subscription → apply FREE allowance.
-- soft_limit_bytes recomputed at 80% of max_bytes (DEFAULT_SOFT_LIMIT_RATIO).
--
-- Only updates rows whose limits would actually change - keeps updated_at
-- stable for orgs already in sync (no cache-eviction storm on replay).

-- Pass 1: orgs with a live owner subscription.
-- Status set aligned with PlanStorageQuotaSyncer.syncAfterCommit (active,
-- trialing, past_due, incomplete) - past_due keeps the paid allowance
-- until status actually flips to canceled.
-- DISTINCT ON guards the (rare) multi-active-subscription corruption case
-- by picking the most recently touched subscription, same heuristic as V198.
WITH owner_plan AS (
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
      AND p.included_storage_bytes > 0
    ORDER BY org.id,
             COALESCE(s.updated_at, s.created_at, TIMESTAMPTZ '1970-01-01') DESC
)
UPDATE storage.organization_storage_quota osq
SET max_bytes        = op.storage_bytes,
    soft_limit_bytes = (op.storage_bytes * 0.8)::BIGINT,
    hard_limit_bytes = op.storage_bytes,
    updated_at       = NOW()
FROM owner_plan op
WHERE op.org_id = osq.organization_id
  AND (osq.max_bytes        <> op.storage_bytes
       OR osq.soft_limit_bytes <> (op.storage_bytes * 0.8)::BIGINT
       OR osq.hard_limit_bytes <> op.storage_bytes);

-- Pass 2: orgs whose owner has no active subscription → FREE allowance.
WITH free_plan AS (
    SELECT included_storage_bytes AS storage_bytes FROM auth.plan WHERE code = 'FREE'
)
UPDATE storage.organization_storage_quota osq
SET max_bytes        = fp.storage_bytes,
    soft_limit_bytes = (fp.storage_bytes * 0.8)::BIGINT,
    hard_limit_bytes = fp.storage_bytes,
    updated_at       = NOW()
FROM free_plan fp,
     auth.organization org
WHERE org.id::text = osq.organization_id
  AND org.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM auth.billing_customer bc
      JOIN auth.subscription s ON s.billing_customer_id = bc.id
      WHERE bc.user_id = org.owner_id
        AND s.status IN ('active', 'trialing')
  )
  AND (osq.max_bytes        <> fp.storage_bytes
       OR osq.soft_limit_bytes <> (fp.storage_bytes * 0.8)::BIGINT
       OR osq.hard_limit_bytes <> fp.storage_bytes);
