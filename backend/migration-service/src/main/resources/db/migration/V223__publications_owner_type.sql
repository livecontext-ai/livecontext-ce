-- V223 - Publication ownership owner_type + owner_id dual-write (#151)
--
-- Context: V8 created `publication.workflow_publications` with `publisher_id
-- VARCHAR(255) NOT NULL` set to the user_id of the human who clicked Publish.
-- There was no `organization_id`, no `owner_type` discriminator, no concept of
-- "workspace this was published from". The "My applications" list endpoint
-- filters `WHERE publisher_id = :userId` so a TEAM workspace's teammate cannot
-- see publications another teammate published from the same workspace.
--
-- This migration adds two new columns:
--   * owner_type - discriminator: 'USER' (personal workspace) or 'ORG' (team
--     workspace). Constrained via CHECK to those two values.
--   * owner_id   - opaque scope id: user_id when owner_type='USER',
--     organization_id when owner_type='ORG'.
--
-- publisher_id is preserved as the audit field - it records the human who
-- clicked Publish even for ORG-owned publications. This is intentional and
-- mirrors PR23/PR27/PR30 strict-isolation patterns where the `tenant_id` /
-- `user_id` audit trail is kept untouched alongside `organization_id`.
--
-- Backfill: every legacy row becomes USER-owned to its publisher. Org workspace
-- users who already published before this migration will need to re-publish to
-- migrate to ORG ownership - there is no automatic way to infer which workspace
-- they were in at publish time (no historical org context is captured).
--
-- Indexes mirror PR27 (agent_repository) / PR30 (workflow_repository) strict-
-- scope access patterns: partial-only on (owner_type, owner_id, ...) since
-- every list query filters on owner_type='ORG' vs owner_type='USER' first.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS + WHERE
-- owner_type IS NULL on the backfill + IF NOT EXISTS on the CHECK constraint.

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS owner_type VARCHAR(10),
    ADD COLUMN IF NOT EXISTS owner_id   VARCHAR(255);

-- Backfill: every legacy row becomes USER-owned to its publisher. Skip rows
-- that already have a non-null owner_type so re-running is safe.
UPDATE publication.workflow_publications
   SET owner_type = 'USER',
       owner_id   = publisher_id
 WHERE owner_type IS NULL;

-- Enforce non-null + value domain AFTER backfill so re-running on a partially
-- migrated DB (rare but legal) does not violate the constraints.
ALTER TABLE publication.workflow_publications
    ALTER COLUMN owner_type SET NOT NULL,
    ALTER COLUMN owner_id   SET NOT NULL;

-- CHECK constraint - Postgres does not support `ADD CONSTRAINT IF NOT EXISTS`.
-- Wrap in DO block so re-runs are idempotent (constraint name fixed at
-- chk_publications_owner_type for grep-ability).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'chk_publications_owner_type'
           AND conrelid = 'publication.workflow_publications'::regclass
    ) THEN
        ALTER TABLE publication.workflow_publications
            ADD CONSTRAINT chk_publications_owner_type
            CHECK (owner_type IN ('USER', 'ORG'));
    END IF;
END$$;

-- Index #1 - "my org's publications" list endpoint
--   WHERE owner_type = 'ORG' AND owner_id = :orgId AND status != 'INACTIVE'
--   ORDER BY published_at DESC
-- (status + visibility predicates may be added depending on the query
-- - leaving them as INCLUDE-style trailing columns keeps the index general.)
CREATE INDEX IF NOT EXISTS idx_publications_owner
    ON publication.workflow_publications (owner_type, owner_id, status, visibility);

-- Index #2 - same scope, ordered by published_at for "recent" listings.
CREATE INDEX IF NOT EXISTS idx_publications_owner_published
    ON publication.workflow_publications (owner_type, owner_id, published_at DESC);

COMMENT ON COLUMN publication.workflow_publications.owner_type IS
    'V223 - publication ownership discriminator: USER (personal workspace) or ORG (team workspace). Pairs with owner_id (user_id when USER, organization_id when ORG). publisher_id remains the audit field (human who clicked Publish).';

COMMENT ON COLUMN publication.workflow_publications.owner_id IS
    'V223 - opaque scope id (user_id when owner_type=USER, organization_id when owner_type=ORG). Used by "my publications" / "Mes applications" list endpoints + scope-aware mutation guards.';
