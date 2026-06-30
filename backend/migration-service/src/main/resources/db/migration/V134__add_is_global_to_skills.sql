-- ============================================================================
-- V134: Add is_global flag to agent.skills
--
-- Admins can mark skills as global so they appear for every tenant (read-only
-- for non-admins), similar to how default skills behave - but managed from the
-- UI instead of being hardcoded in DefaultSkillsProvider.
-- ============================================================================

ALTER TABLE agent.skills
    ADD COLUMN IF NOT EXISTS is_global BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index: most skills are per-tenant; only the small set of globals
-- benefits from an index when unioning them into listSkills queries.
CREATE INDEX IF NOT EXISTS idx_skills_is_global
    ON agent.skills (is_global)
    WHERE is_global = TRUE;
