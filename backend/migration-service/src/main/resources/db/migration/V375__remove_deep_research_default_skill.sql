-- Remove the "Deep Research" built-in default skill.
--
-- DefaultSkillsProvider no longer registers any built-in default: global skills are now
-- distributed from the cloud via the signed skill bundle (V374), so the platform stopped
-- shipping a hard-coded per-tenant default. Every tenant's previously seeded deep_research
-- row is now orphaned, so delete them all here. This ALSO clears any copy whose
-- `instructions` were corrupted into a bare number (e.g. "97559") by the pre-fix
-- getString().toString() content bug, which is fixed at the write path via getText().
--
-- Scope note: this deletes user-EDITED deep_research rows too - intended, the feature is
-- being removed. Bundle-applied global skills are untouched (they carry source_bundle_key
-- and is_global, never a default_key), as are any admin-created globals.
SET search_path TO agent;

-- agent_skills has no FK to skills (V5 defines skill_id as a plain UUID), so a skill delete
-- would leave dangling assignments. Clear the deep_research assignments first.
DELETE FROM agent_skills
 WHERE skill_id IN (SELECT id FROM skills WHERE default_key = 'deep_research');

-- user_skill_overrides FK-cascades on skill delete (V276 ON DELETE CASCADE), so the
-- override rows are cleaned automatically by the delete below.
DELETE FROM skills
 WHERE default_key = 'deep_research';
