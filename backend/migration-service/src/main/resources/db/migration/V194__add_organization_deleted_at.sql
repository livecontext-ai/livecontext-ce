-- PR-cascade simplified: soft-delete organizations.
--
-- Adds an Organization.deleted_at TIMESTAMPTZ NULL column so OWNER-initiated
-- DELETE /organizations/{id} can mark the org as soft-deleted without an
-- expensive cross-service cascade (PR-cascade.1 will handle the full purge).
--
-- The base table is auth.organization (singular, cf. V3__create_auth_schema.sql:151).
--
-- Existing rows have deleted_at=NULL → unchanged behaviour. A future
-- ShedLock cron will hard-delete rows with deleted_at older than the grace
-- period (30 days target, per plan v8 §PR-cascade D3 = soft-30j).
--
-- The partial index lets queries that need to skip soft-deleted orgs
-- (the common path) stay fast without a sequential scan.

ALTER TABLE auth.organization
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE NULL;

CREATE INDEX idx_org_active
    ON auth.organization(id)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN auth.organization.deleted_at IS
    'Soft-delete timestamp. NULL = active org. Set by DELETE /organizations/{id}. '
    'Hard purge after 30 days by the OrgRetentionPurge cron (PR-cascade.1 follow-up).';
