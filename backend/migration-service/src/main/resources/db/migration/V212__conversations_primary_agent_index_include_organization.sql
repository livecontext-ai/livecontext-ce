-- PR21 R2 - recreate V115's primary-agent unique index to include organization_id.
--
-- Context: V115 enforces "one primary conversation per (agent_id, user_id)" via a
-- partial unique index that excludes the three legitimate N:1 cases (sub-agent
-- chains, memory-off webhook/schedule rows, soft-deleted rows). It was written
-- before org workspaces existed, so the key is workspace-blind.
--
-- The bug class PR21 R2 closes:
--   • A user who already has a personal-scope chat for agent-X cannot create
--     an org-scope chat for the same agent - V115 fires on `(agent_id,
--     user_id)` regardless of which workspace the new row would land in.
--     PR21 R2's race-loser re-fetch is scope-aware: it queries the SAME
--     workspace's strict finder, finds nothing (the colliding row is in the
--     OTHER scope), and rethrows DataIntegrityViolationException → 500.
--   • The new "one agent = one team conversation per org" contract has no
--     DB-level enforcement - two concurrent team members could both insert
--     because their user_ids differ. Today the app-level idempotency check
--     in createAgentConversation handles this in the happy path, but the
--     DB invariant is needed to make the race-loser path correct.
--
-- This migration:
--   1. DROP the old uq_conversations_primary_agent_per_user index (workspace-blind).
--   2. CREATE a new partial unique index that distinguishes by workspace.
--      We key on `(agent_id, user_id, COALESCE(organization_id, ''))` so the
--      NULL/personal scope is a distinct partition from any org_id string -
--      this lets the SAME (user, agent) pair have one personal row AND one
--      row per org workspace, all subject to the partial WHERE.
--   3. For the org workspace's "one agent = one team conversation" contract,
--      we add a sibling partial unique index keyed on `(agent_id,
--      organization_id)` (no user_id) so two team members cannot both insert
--      a primary row for the same (agent, org) pair. user_id stays on the
--      row as the creator audit; the contract is enforced by the org index.
--
-- Lock posture:
--   - DROP INDEX CONCURRENTLY → SHARE UPDATE EXCLUSIVE. No row rewrite.
--   - CREATE UNIQUE INDEX CONCURRENTLY → also SHARE UPDATE EXCLUSIVE; rejects
--     existing duplicates if any (the V115 dedup step + V211 NULL-default
--     mean there should be none, but the migration FAILS LOUDLY if a stale
--     duplicate slipped through).
--
-- Idempotent: DROP INDEX IF EXISTS + CREATE UNIQUE INDEX IF NOT EXISTS.

-- flyway:executeInTransaction=false

-- 1. Drop the legacy workspace-blind unique index.
DROP INDEX CONCURRENTLY IF EXISTS conversation.uq_conversations_primary_agent_per_user;

-- 2. Re-create with workspace partitioning. COALESCE(organization_id, '')
--    folds the NULL (personal scope) bucket into a distinct partition from
--    any non-empty org_id string. The empty-string is impossible in
--    organization_id (PR21 entity/dto stamps either NULL or a non-blank UUID),
--    so there is no collision risk with the placeholder.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_conversations_primary_agent_per_user_workspace
    ON conversation.conversations (agent_id, user_id, COALESCE(organization_id, ''))
    WHERE agent_id IS NOT NULL
      AND parent_conversation_id IS NULL
      AND memory_enabled IS TRUE
      AND active IS TRUE;

-- 3. Sibling index for the org workspace's "one agent = one team conversation"
--    contract. Two team members cannot both insert a primary row for the same
--    (agent, org) pair. Personal scope (organization_id IS NULL) is excluded
--    by the partial WHERE - personal "one agent = one user conversation" is
--    already enforced by index #2 above.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_conversations_primary_agent_per_org
    ON conversation.conversations (agent_id, organization_id)
    WHERE agent_id IS NOT NULL
      AND organization_id IS NOT NULL
      AND parent_conversation_id IS NULL
      AND memory_enabled IS TRUE
      AND active IS TRUE;

COMMENT ON INDEX conversation.uq_conversations_primary_agent_per_user_workspace IS
    'PR21 R2 - replaces V115 uq_conversations_primary_agent_per_user. Workspace-'
    'partitioned via COALESCE(organization_id, ''''): each (agent, user) pair '
    'can hold one personal-scope primary row AND one row per org workspace. '
    'Both subject to the partial WHERE (memory on, not a sub-agent, not soft-'
    'deleted).';

COMMENT ON INDEX conversation.uq_conversations_primary_agent_per_org IS
    'PR21 R2 - enforces "one agent = one team conversation per org workspace". '
    'When organization_id IS NOT NULL, every member of the org converges on '
    'the same shared conversation row for a given agent, regardless of which '
    'member created it. Without this, two concurrent team members could each '
    'insert their own primary row (V115 keyed on user_id only).';
