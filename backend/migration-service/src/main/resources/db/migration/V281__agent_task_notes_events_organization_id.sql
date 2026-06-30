-- V281 - defense-in-depth: stamp organization_id on agent_task_notes / agent_task_events
--
-- Both tables (V67) carry task_id with ON DELETE CASCADE to agent_tasks but have no
-- organization_id of their own. The defense at the controller layer (caller validates the
-- parent task is in scope before reading the children) holds today, but is fragile to:
--   • a new endpoint added without the parent-check (regression risk)
--   • a debug or admin tool path that fetches notes/events by id without going through
--     the task gate
--   • any future repository method that joins notes/events into a cross-org aggregate
--
-- Adding the column + backfilling from parent + NOT NULL aligns these subtables with the
-- V261-V263 platform-wide org-isolation rollout.

-- ---------------------------------------------------------------------------
-- Step 1 - add nullable column so subsequent UPDATE doesn't fail per-row.
-- ---------------------------------------------------------------------------
ALTER TABLE agent.agent_task_notes  ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);
ALTER TABLE agent.agent_task_events ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

-- ---------------------------------------------------------------------------
-- Step 2 - backfill from parent task. After V262 every agent_tasks row carries a
--          non-null organization_id, so this single UPDATE per table covers the corpus.
-- ---------------------------------------------------------------------------
UPDATE agent.agent_task_notes  n
   SET organization_id = t.organization_id
  FROM agent.agent_tasks t
 WHERE t.id = n.task_id
   AND n.organization_id IS NULL;

UPDATE agent.agent_task_events e
   SET organization_id = t.organization_id
  FROM agent.agent_tasks t
 WHERE t.id = e.task_id
   AND e.organization_id IS NULL;

-- ---------------------------------------------------------------------------
-- Step 3 - purge orphans. Should be empty given the FK + ON DELETE CASCADE, but
--          defensive belt-and-braces against historical inconsistency before the
--          NOT NULL constraint lands. An orphan = a note/event whose parent task
--          is gone; the row is already unreachable through the normal lookup path
--          (task_id has no parent), so deletion is safe.
-- ---------------------------------------------------------------------------
DELETE FROM agent.agent_task_notes  WHERE organization_id IS NULL;
DELETE FROM agent.agent_task_events WHERE organization_id IS NULL;

-- ---------------------------------------------------------------------------
-- Step 4 - promote to NOT NULL. Matches the V263 contract for the parent table.
-- ---------------------------------------------------------------------------
ALTER TABLE agent.agent_task_notes  ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.agent_task_events ALTER COLUMN organization_id SET NOT NULL;

-- ---------------------------------------------------------------------------
-- Step 5 - supporting indexes for org-scoped browse queries.
--          The existing (task_id, created_at) index covers the read-by-task path;
--          add (organization_id, task_id, created_at) for org-scoped aggregates and
--          to make WHERE task_id = ? AND organization_id = ? a single index scan.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_task_notes_org_task_created
    ON agent.agent_task_notes(organization_id, task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_task_events_org_task_created
    ON agent.agent_task_events(organization_id, task_id, created_at);
