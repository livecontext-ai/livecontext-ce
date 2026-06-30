-- ============================================================================
-- V70: Backfill agent task delegation tables
-- ============================================================================
-- V67 ("create agent tasks") is recorded in flyway_schema_history as successfully
-- applied on 2026-04-10, but the `script` column in history still points to an
-- older filename (V67__add_split_coalesce_index.sql) from a previous state of the
-- branch. Because Flyway keys on version number and considers V67 already applied,
-- the current V67__create_agent_tasks.sql never actually ran - leaving agent-service
-- crashing at boot on `relation "agent.agent_task_recurrences" does not exist`.
--
-- This migration backfills the missing tables. Content is identical to V67 minus
-- the `agent.shedlock` block (already created by V69). All statements are idempotent
-- (IF NOT EXISTS) so running this alongside environments where V67 did create the
-- tables is a no-op.
--
-- See the project docs for the functional design.
-- ============================================================================

SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- agent_task_recurrences - must be created first (FK target from agent_tasks)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.agent_task_recurrences (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            TEXT NOT NULL,
    created_by_agent_id  UUID REFERENCES agent.agents(id) ON DELETE CASCADE,
    created_by_user_id   TEXT,
    -- Template fields (used to instantiate each task)
    target_agent_id      UUID REFERENCES agent.agents(id) ON DELETE CASCADE,  -- NULL = backlog
    title                VARCHAR(500) NOT NULL,
    instructions         TEXT NOT NULL,
    task_context         JSONB,
    priority             VARCHAR(10) NOT NULL DEFAULT 'normal'
        CHECK (priority IN ('low','normal','high','urgent')),
    -- Schedule
    cron_expression      VARCHAR(100) NOT NULL,
    timezone             VARCHAR(50) NOT NULL DEFAULT 'UTC',
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    last_fired_at        TIMESTAMPTZ,
    next_fire_at         TIMESTAMPTZ NOT NULL,
    fire_count           BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_recurrences_due
    ON agent.agent_task_recurrences(next_fire_at)
    WHERE enabled = TRUE;

CREATE INDEX IF NOT EXISTS idx_task_recurrences_tenant
    ON agent.agent_task_recurrences(tenant_id);

CREATE INDEX IF NOT EXISTS idx_task_recurrences_creator
    ON agent.agent_task_recurrences(created_by_agent_id);

-- ---------------------------------------------------------------------------
-- agent_tasks
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.agent_tasks (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             TEXT NOT NULL,
    parent_task_id        UUID REFERENCES agent.agent_tasks(id) ON DELETE SET NULL,
    created_by_agent_id   UUID REFERENCES agent.agents(id) ON DELETE SET NULL,
    created_by_user_id    TEXT,
    -- NULL assignee = backlog (any eligible agent can claim)
    assigned_to_agent_id  UUID REFERENCES agent.agents(id) ON DELETE CASCADE,
    recurrence_id         UUID REFERENCES agent.agent_task_recurrences(id) ON DELETE SET NULL,
    title                 VARCHAR(500) NOT NULL,
    instructions          TEXT NOT NULL,
    task_context          JSONB,
    priority              VARCHAR(10) NOT NULL DEFAULT 'normal'
        CHECK (priority IN ('low','normal','high','urgent')),
    status                VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','in_progress','completed','failed','cancelled')),
    result                TEXT,
    error_message         TEXT,
    depth                 INT NOT NULL DEFAULT 0,
    due_by                TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ
);

-- Assigned inbox lookup (skip backlog rows)
CREATE INDEX IF NOT EXISTS idx_agent_tasks_assigned
    ON agent.agent_tasks(assigned_to_agent_id, status)
    WHERE assigned_to_agent_id IS NOT NULL;

-- Outbox lookup
CREATE INDEX IF NOT EXISTS idx_agent_tasks_created_by
    ON agent.agent_tasks(created_by_agent_id, status);

-- Tenant fan-out
CREATE INDEX IF NOT EXISTS idx_agent_tasks_tenant
    ON agent.agent_tasks(tenant_id, status);

-- Cascade / parent traversal
CREATE INDEX IF NOT EXISTS idx_agent_tasks_parent
    ON agent.agent_tasks(parent_task_id);

-- Backlog index - only rows without an assignee that are pickable
CREATE INDEX IF NOT EXISTS idx_agent_tasks_backlog
    ON agent.agent_tasks(tenant_id, priority, created_at)
    WHERE assigned_to_agent_id IS NULL AND status = 'pending';

-- ---------------------------------------------------------------------------
-- agent_task_notes
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.agent_task_notes (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id          UUID NOT NULL REFERENCES agent.agent_tasks(id) ON DELETE CASCADE,
    author_agent_id  UUID,
    author_user_id   TEXT,
    content          TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_notes_task
    ON agent.agent_task_notes(task_id, created_at);

-- ---------------------------------------------------------------------------
-- agent_task_events - audit trail
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent.agent_task_events (
    id          BIGSERIAL PRIMARY KEY,
    task_id     UUID NOT NULL REFERENCES agent.agent_tasks(id) ON DELETE CASCADE,
    event_type  VARCHAR(30) NOT NULL,
    actor_type  VARCHAR(10) NOT NULL CHECK (actor_type IN ('agent','user','system')),
    actor_id    TEXT,
    old_value   JSONB,
    new_value   JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_events_task
    ON agent.agent_task_events(task_id, created_at);
