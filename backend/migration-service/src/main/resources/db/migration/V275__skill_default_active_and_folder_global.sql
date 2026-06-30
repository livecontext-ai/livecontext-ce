-- ============================================================================
-- V275: DB-backed skill default activation + folder global visibility.
--
-- Background (2026-05-21): chat skill activation used to live in the browser
-- localStorage (frontend/hooks/useDefaultSkills.ts), seeded from the
-- hard-coded `default_key` set. That left the user with no way to control
-- which skills the general chat agent gets in a NEW conversation: the seed
-- was static, multi-device state diverged, and the chat backend
-- (AgentContextBuilder) fell back to ALL core tools when no defaultSkillIds
-- arrived from the client.
--
-- This migration moves activation to DB so the answer is the same for every
-- device and every member of an (tenant, organization) pair:
--
--   * agent.skills.is_default_active
--       When TRUE, the skill is auto-included in a new chat conversation for
--       every member of the skill's existing visibility scope:
--         - non-global skill -> the (tenant_id, organization_id) it lives in
--         - global skill    -> every tenant × org
--       Owner sets it (no admin gate) on personal skills; admin sets it on
--       global skills, same gating as `is_global` itself.
--
--   * agent.skill_folders.is_global
--       Independent of child skill `is_global` (no cascade). Admin-only toggle
--       - same shape as V134's `agent.skills.is_global`. Used purely for UI
--       grouping; child skill visibility still follows each skill's own flags.
--
-- Backfill rationale: skills with `default_key` set are LiveContext's built-in
-- "deep_research" / "workflow" / etc. - they were the implicit default-active
-- set under the old localStorage seed. Flip those to is_default_active=TRUE
-- so existing tenants keep the same out-of-the-box chat behavior.
-- ============================================================================

ALTER TABLE agent.skills
    ADD COLUMN IF NOT EXISTS is_default_active BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent.skill_folders
    ADD COLUMN IF NOT EXISTS is_global BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: every legacy built-in default skill keeps being default-active so
-- existing users see no behavior change after the localStorage seed is dropped.
-- BUT: respect a user who already disabled the skill at the tenant level -
-- those rows have `is_active=FALSE` and the legacy localStorage seed also
-- would have left them out of the effective set after the user toggled them
-- off; flipping is_default_active=TRUE here would re-enable them silently.
UPDATE agent.skills
   SET is_default_active = TRUE
 WHERE default_key IS NOT NULL
   AND is_default_active = FALSE
   AND (is_active IS NULL OR is_active = TRUE);

-- Partial index: only the (typically small) default-active subset is hit on
-- every new-chat lookup; non-default rows skip the index entirely.
CREATE INDEX IF NOT EXISTS idx_skills_is_default_active
    ON agent.skills (is_default_active)
 WHERE is_default_active = TRUE;

-- Partial index mirroring V134's idx_skills_is_global pattern - folder globals
-- are a small admin-curated set and a partial index keeps the row count low.
CREATE INDEX IF NOT EXISTS idx_skill_folders_is_global
    ON agent.skill_folders (is_global)
 WHERE is_global = TRUE;
