-- ============================================================================
-- V320: backfill is_default_active for built-in skills seeded AFTER V275.
--
-- Background: V275 (2026-05-21) introduced agent.skills.is_default_active and
-- backfilled the built-in "deep_research" / "workflow" / etc. rows
-- (default_key IS NOT NULL) to TRUE so existing tenants kept their
-- out-of-the-box active default skills. BUT SkillService.seedDefaultSkills()
-- - the per-tenant auto-seeder that runs on first skills list for a NEW tenant
-- - was never updated to set the flag, so every tenant created between V275 and
-- this fix got its default skills with is_default_active=FALSE → unchecked by
-- default for the user. The code fix sets it on seed going forward; this
-- migration repairs the tenants already seeded with FALSE.
--
-- Same shape and safety as the V275 backfill: only flip rows the user did not
-- already disable (is_active=FALSE means the user toggled the skill off - leave
-- those out so we don't silently re-enable them).
-- ============================================================================

UPDATE agent.skills
   SET is_default_active = TRUE
 WHERE default_key IS NOT NULL
   AND is_default_active = FALSE
   AND (is_active IS NULL OR is_active = TRUE);
