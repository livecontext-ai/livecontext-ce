-- V334 (2026-06-11) - back the default-skill seed idempotence with a real constraint.
--
-- seedDefaultSkills() is matched on (tenant_id, default_key) but no unique
-- constraint ever existed: the check-then-insert was racy. Pre-V334 the race
-- was cold (the Phase 6c org-aware listSkills regression meant the seed never
-- fired in real traffic); the same release that revives the seed on every
-- brand-new tenant's first skills list makes the race hot - two concurrent
-- first fetches could double-insert built-ins the user cannot delete
-- (deleteSkill refuses default_key rows).
--
-- Steps (sequential - same-table data-modifying CTEs are order-unsafe):
--   1) collect duplicate rows (keep the oldest per (tenant_id, default_key)),
--   2) repoint user_skill_overrides at the keeper unless that would collide
--      on the (user_id, skill_id) PK (colliding rows die with the loser via
--      the ON DELETE CASCADE FK),
--   3) repoint agent_skills the same way; agent_skills has NO FK on skill_id,
--      so whatever could not be repointed is deleted instead of dangling,
--   4) delete the loser skills,
--   5) partial unique index so concurrent seeders now fail loudly instead of
--      duplicating (the service skips conflicts via a REQUIRES_NEW insert).

CREATE TEMP TABLE _v334_losers ON COMMIT DROP AS
SELECT id, keeper_id FROM (
    SELECT id,
           first_value(id) OVER (PARTITION BY tenant_id, default_key ORDER BY created_at ASC, id ASC) AS keeper_id
    FROM agent.skills
    WHERE default_key IS NOT NULL
) ranked
WHERE id <> keeper_id;

-- DISTINCT ON picks at most ONE loser row per (owner, keeper) target: with
-- ≥2 losers in the same (tenant_id, default_key) group referenced by the same
-- user/agent, repointing both in one statement would itself collide on the
-- target table's PK/unique. The non-picked rows die with their loser skill
-- (FK cascade for overrides, explicit delete for agent_skills below).
UPDATE agent.user_skill_overrides o
SET skill_id = pick.keeper_id
FROM (
    SELECT DISTINCT ON (o2.user_id, l2.keeper_id)
           o2.user_id, o2.skill_id, l2.keeper_id
    FROM agent.user_skill_overrides o2
    JOIN _v334_losers l2 ON l2.id = o2.skill_id
    WHERE NOT EXISTS (
        SELECT 1 FROM agent.user_skill_overrides k
        WHERE k.user_id = o2.user_id AND k.skill_id = l2.keeper_id
    )
    ORDER BY o2.user_id, l2.keeper_id, o2.skill_id
) pick
WHERE o.user_id = pick.user_id
  AND o.skill_id = pick.skill_id;

UPDATE agent.agent_skills a
SET skill_id = pick.keeper_id
FROM (
    SELECT DISTINCT ON (a2.agent_id, l2.keeper_id)
           a2.id AS agent_skill_id, l2.keeper_id
    FROM agent.agent_skills a2
    JOIN _v334_losers l2 ON l2.id = a2.skill_id
    WHERE NOT EXISTS (
        SELECT 1 FROM agent.agent_skills k
        WHERE k.agent_id = a2.agent_id AND k.skill_id = l2.keeper_id
    )
    ORDER BY a2.agent_id, l2.keeper_id, a2.id
) pick
WHERE a.id = pick.agent_skill_id;

DELETE FROM agent.agent_skills a
USING _v334_losers l
WHERE a.skill_id = l.id;

DELETE FROM agent.skills s
USING _v334_losers l
WHERE s.id = l.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_skills_tenant_default_key
    ON agent.skills (tenant_id, default_key)
    WHERE default_key IS NOT NULL;
