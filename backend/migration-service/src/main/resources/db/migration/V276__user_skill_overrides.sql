-- ============================================================================
-- V276: Per-user skill activation override.
--
-- Companion to V275 (skills.is_default_active). When the admin/owner marks a
-- skill default-active, every user in scope sees it active by default - but
-- the user must still be able to opt-out *for themselves* without affecting
-- teammates. This table holds those per-user overrides.
--
-- Resolution at chat-time (conversation-service AgentContextBuilder):
--
--   effective_active(user, skill) =
--       COALESCE(user_skill_overrides.active, skills.is_default_active)
--
-- Row absent → fall back to the skill's default. Row present → override wins.
-- The "reactivate just for THIS conversation" UX layered on top is handled by
-- the existing `defaultSkillIds` payload in the chat request (MessageComposer
-- per-conv toggles); the override table is the *persistent* per-user layer.
--
-- Why a separate table and not a JSONB column on user/profile: skill_id is a
-- FK to agent.skills, so cascade delete cleans up automatically when a skill
-- is removed (otherwise we'd leak stale overrides forever).
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent.user_skill_overrides (
    user_id    VARCHAR(255) NOT NULL,
    skill_id   UUID         NOT NULL,
    active     BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, skill_id),
    CONSTRAINT fk_user_skill_overrides_skill
        FOREIGN KEY (skill_id) REFERENCES agent.skills (id) ON DELETE CASCADE
);

-- No separate (user_id) index is needed - the PK (user_id, skill_id) covers
-- prefix scans for the "list every override for this user" chat-seeding hot
-- path. An additional (user_id) index would be strict-redundant with the PK
-- prefix and never get picked by the planner.
