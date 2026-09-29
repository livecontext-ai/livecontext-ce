-- V547: one PENDING invitation per (organization, email), instead of one row per status.
--
-- V3 declared UNIQUE (organization_id, email, status). That allows at most ONE ACCEPTED and
-- ONE CANCELLED row per email and organization, forever. Consequences:
--   * re-inviting a member who was removed (or left) fails with a 500 on accept, because the
--     accept flips the new row to ACCEPTED next to the old ACCEPTED one;
--   * a second cancel / decline for the same email fails the same way (second CANCELLED row).
-- The only rule the application needs is "no two PENDING invitations for the same address in
-- the same organization" (OrganizationMemberService.inviteMember checks it too), so the plain
-- constraint is replaced by a partial unique index on PENDING rows.
--
-- Safe on existing data: the old constraint already forbade two PENDING rows for the same
-- (organization_id, email), so the partial index cannot fail to build.
--
-- Every reference is schema-qualified: beforeEachMigrate resets search_path to orchestrator.
-- Idempotent.

ALTER TABLE auth.organization_invitation
    DROP CONSTRAINT IF EXISTS organization_invitation_organization_id_email_status_key;

-- Belt and braces for an install where the V3 constraint got another name: drop any remaining
-- UNIQUE constraint on exactly (organization_id, email, status).
DO $$
DECLARE
    con RECORD;
BEGIN
    FOR con IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'auth'
          AND t.relname = 'organization_invitation'
          AND c.contype = 'u'
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM pg_attribute a
               WHERE a.attrelid = t.oid AND a.attnum = ANY (c.conkey))
              = ARRAY['email', 'organization_id', 'status']
    LOOP
        EXECUTE format('ALTER TABLE auth.organization_invitation DROP CONSTRAINT %I', con.conname);
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_organization_invitation_pending
    ON auth.organization_invitation (organization_id, email)
    WHERE status = 'PENDING';
