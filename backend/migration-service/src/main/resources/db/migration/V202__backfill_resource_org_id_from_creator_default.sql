-- PR12 - backfill organization_id on resource tables from creator's default org.
--
-- Problem: the org-scoped read filter `findByOrganizationOrOwner(orgId, userId)`
-- shipped to agents/data_sources/interfaces/workflows/projects relies on
-- `organization_id` being populated. New rows are tagged correctly (controllers
-- pass X-Organization-ID from gateway), but ALL legacy rows have
-- `organization_id = NULL` - they're only visible to their original creator
-- (tenant_id), invisible to other org members.
--
-- Symptom (user-reported 2026-05-12): "je suis sur team et pas accès au
-- workflow de l'organisation qui sont censés appartenir à l'owner". A member
-- switches to an org they belong to but sees nothing - the owner's workflows
-- have NULL organization_id and the filter org_id=current OR tenant_id=me
-- matches neither.
--
-- Fix: for each resource with `organization_id IS NULL` AND a numeric
-- `tenant_id` that resolves to a known user with a default-org membership,
-- set `organization_id` to that user's CURRENT default org. Best-effort -
-- we can't reconstruct historical org context at creation time, but the
-- current default is the right proxy for "where the user thought they were
-- working".
--
-- Skipped:
-- - Rows with non-numeric tenant_id (legacy/UUID/garbage)
-- - Users with no default org membership (degenerate state)
-- - Rows where organization_id is already populated (idempotent)
--
-- Live-run audit (prod, 2026-05-12 19:50 UTC):
--   workflows:    22 backfilled
--   agents:        7 backfilled
--   data_sources: 15 backfilled
--   interfaces:   51 backfilled
--   total:        95
-- This migration replays the same logic on any subsequent fresh setup or
-- recovery from backup that predates the live patch.
--
-- Note (round-5 post-mortem 2026-05-12 20:17 UTC): an earlier draft of this
-- file also included a UPDATE against `orchestrator.projects`. That table
-- has NO `tenant_id` column - it uses `owner_id` - so the deploy crashed
-- with `ERROR: column p.tenant_id does not exist` (SQL state 42703). The
-- live-run earlier in the day had correctly skipped projects (no
-- `projects: X backfilled` line in the audit above). The projects block
-- was post-hoc completion-bias on my part - adding what I thought "should"
-- be there without verifying the schema. Removed in this revision to make
-- the file a faithful transcript of the live run. If projects ever need
-- the same backfill, that's a separate migration with the correct column
-- name (`p.owner_id`) and explicit prior schema verification.

-- Workflows
UPDATE orchestrator.workflows w
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE w.organization_id IS NULL
   AND w.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true);

-- Agents
UPDATE agent.agents a
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = a.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE a.organization_id IS NULL
   AND a.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = a.tenant_id::bigint AND om.is_default = true);

-- DataSources
UPDATE datasource.data_sources d
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = d.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE d.organization_id IS NULL
   AND d.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = d.tenant_id::bigint AND om.is_default = true);

-- Interfaces
UPDATE interface.interfaces i
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = i.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE i.organization_id IS NULL
   AND i.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = i.tenant_id::bigint AND om.is_default = true);

-- Projects: deliberately NOT backfilled here - orchestrator.projects has
-- `owner_id` (varchar 50), not `tenant_id`. The live run earlier today
-- correctly skipped this table. A future migration can do projects backfill
-- with `p.owner_id` if needed; out of scope for this V202.

COMMENT ON COLUMN orchestrator.workflows.organization_id IS
    'Org that owns this workflow. NULL = personal/legacy (visible only to '
    'tenant_id). When set, ALL members of the org see the workflow via '
    'findByOrganizationOrOwner. Set from X-Organization-ID at create-time '
    'by WorkflowCrudController. V202 backfilled legacy NULL rows from each '
    'creator''s default-org membership.';
