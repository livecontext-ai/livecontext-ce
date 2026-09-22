-- V502: give each workspace quota row the account that owns it, so storage can enforce ONE
-- shared allowance per account instead of one per workspace.
--
-- The problem this opens the door to fixing
-- ----------------------------------------
-- storage.organization_storage_quota.max_bytes carries the owner's plan allowance, and the gate
-- compares a workspace's own used_bytes against it. Each workspace therefore gets the FULL plan
-- allowance to itself: a TEAM account (100 GB, 10 workspaces allowed) can hold 1 TB, and PAYG or
-- ENTERPRISE, which cap no workspace count at all, are unbounded. Measured in production
-- 2026-09-19: 910 GB granted across live workspaces for 22 GB actually stored, and one TEAM
-- account holding 300 GB of grants on a plan sold as 100 GB.
--
-- Enforcing a per-ACCOUNT ceiling needs the set of workspaces one account owns. That mapping
-- lives in auth.organization.owner_id, and storage-service may not read the auth schema
-- (cross-schema SQL is forbidden, and an HTTP hop per upload is not an option on a write path).
-- So the owner is denormalised onto the quota row: one indexed SUM inside the storage schema
-- then answers "how much has this whole account stored".
--
-- Nullable on purpose
-- -------------------
-- A NULL account_id means "not yet attributed", and the enforcement path falls back to the
-- per-workspace behaviour that shipped before. That is what makes this migration safe to deploy
-- on its own, ahead of the code that reads it: nothing changes until a row has an owner, and a
-- row that somehow never gets one keeps working exactly as it does today rather than failing
-- closed and blocking uploads.
--
-- Kept in sync by auth-service, which is the only writer of these rows and the only service that
-- knows the owner: PlanStorageQuotaSyncer on workspace birth and on plan change, and
-- OrganizationMemberService.transferOwnership, which re-syncs the workspace into the NEW owner's
-- pool. That last one matters even though the UI hides transfer behind `false &&`, because the
-- REST endpoint is live: without it a transferred workspace would keep charging its bytes to the
-- previous owner's pool while the new owner stored in it for free.

ALTER TABLE storage.organization_storage_quota
    ADD COLUMN IF NOT EXISTS account_id VARCHAR(255);

-- When the ALLOWANCE on this row was last written, as opposed to updated_at, which every upload
-- and every reconciliation bumps because they rewrite used_bytes.
--
-- The pool needs one ceiling for the account, and its rows can disagree: one lagging low (a row
-- materialised at the FREE default, or a syncer write that failed and was swallowed) or one
-- lagging high (a half-applied downgrade). The freshest ALLOWANCE settles both. Ordering by
-- updated_at instead would mean "the workspace someone wrote to most recently", which says
-- nothing about whose limit is current, and it fails viciously: one small upload into a stale
-- 100 MB workspace would make that row the freshest, drop the whole account's ceiling to 100 MB,
-- refuse every workspace at once, and leave no way back, since repairing it needs a write that
-- is now blocked.
ALTER TABLE storage.organization_storage_quota
    ADD COLUMN IF NOT EXISTS limits_updated_at TIMESTAMPTZ;

COMMENT ON COLUMN storage.organization_storage_quota.limits_updated_at IS
    'When max/soft/hard were last written. Distinct from updated_at, which usage writes bump: '
    'the account''s ceiling is taken from the row whose ALLOWANCE is freshest.';

-- Deliberately NOT backfilled. Every candidate is a worse-than-nothing guess:
--   * updated_at is the USAGE clock, so seeding from it would order existing rows exactly the
--     way this column exists to avoid, and a stale 100 MB workspace that happened to be written
--     to last would set the whole account's ceiling;
--   * created_at ranks a lazily-created stale row ABOVE the correctly-synced older one, which
--     is the same failure by the other door.
-- Left NULL, the account's ceiling falls back to the LARGEST of its rows until a real allowance
-- write lands (workspace creation, plan change, ownership transfer), which is safe in the only
-- direction that matters: it can over-grant briefly, never lock an account out of its storage.

COMMENT ON COLUMN storage.organization_storage_quota.account_id IS
    'auth.organization.owner_id of the workspace this row belongs to, denormalised so the '
    'storage schema can sum one account''s workspaces without reading the auth schema. NULL '
    'means unattributed: the quota gate then falls back to per-workspace enforcement.';

-- Backfill every live workspace. Soft-deleted ones are left NULL: they cannot be written to, so
-- they must not consume the account's shared allowance either.
UPDATE storage.organization_storage_quota osq
SET account_id = org.owner_id::text
FROM auth.organization org
WHERE org.id::text = osq.organization_id
  AND org.deleted_at IS NULL
  AND osq.account_id IS DISTINCT FROM org.owner_id::text;

-- The enforcement query is "sum used_bytes where account_id = ?", run on every write.
CREATE INDEX IF NOT EXISTS idx_org_storage_quota_account
    ON storage.organization_storage_quota (account_id)
    WHERE account_id IS NOT NULL;
