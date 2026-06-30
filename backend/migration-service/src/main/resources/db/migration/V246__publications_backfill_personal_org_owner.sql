-- V246 - Backfill legacy USER-owned publications to their publisher's personal org.
--
-- Context: V223 added owner_type + owner_id to publication.workflow_publications
-- and backfilled every legacy row as (owner_type='USER', owner_id=publisher_id).
-- The original V223 comment (lines 21-24) explicitly warned that pre-V223
-- publications cannot be auto-rerouted to an org because no historical org
-- context is captured at publish time.
--
-- In practice, after the org rollout, every onboarded user has a personal org
-- auto-provisioned by OrganizationService.createPersonalOrganization
-- (auth.organization.is_personal=true). The frontend hydrates
-- useCurrentOrgStore with that personal org on first boot and apiClient sends
-- X-Active-Organization-ID on every call. PublicationListQueryService.findByScope
-- then filters WHERE owner_type='ORG' AND owner_id=:orgId - and matches zero
-- legacy rows. The "Mes applications" tab appears empty.
--
-- This migration backfills the missing link: every USER-owned legacy row
-- (where owner_id = publisher_id, i.e. untouched since V223) is rerouted to
-- the publisher's personal org. After this runs, the org-scoped query
-- returns the expected rows when the caller is in their personal workspace.
--
-- Scope of the UPDATE: the predicate (owner_type='USER' AND
-- owner_id = publisher_id) is intentionally broad. It matches BOTH:
--   (a) pre-V223 legacy rows that V223 backfilled with publisher_id,
--   (b) post-V223 personal-mode publishes (organizationId=null at publish
--       time → WorkflowPublicationService.assignOwnerFromContext stamps
--       owner_type='USER', owner_id=publisher_id).
-- This is by design: the auto-provisioned personal org IS the canonical
-- personal scope post-rollout, so every USER-owned-by-self row belongs in
-- that personal org. Rows where owner_type='USER' but owner_id != publisher_id
-- (none currently exist in prod by construction) are deliberately skipped.
--
-- Idempotency: the WHERE clause filters on owner_type='USER' AND
-- owner_id = publisher_id, so re-running is a no-op (rows already migrated
-- to ORG no longer match the predicate). The personal-org join is also
-- defensive - rows whose publisher has no personal org (deleted user,
-- orphan row, very old fixture) are skipped, not corrupted.
--
-- No data loss path: publisher_id is preserved as the audit field (V223
-- contract), so the original human authorship is still recoverable if the
-- migration ever needs to be reversed.

UPDATE publication.workflow_publications p
   SET owner_type = 'ORG',
       owner_id   = o.id::text
  FROM auth.organization o
 WHERE o.is_personal      = true
   AND o.deleted_at       IS NULL
   AND o.owner_id::text   = p.publisher_id
   AND p.owner_type       = 'USER'
   AND p.owner_id         = p.publisher_id;
