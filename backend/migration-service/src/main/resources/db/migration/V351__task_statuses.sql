-- ============================================================================
-- V349: Customizable task-board statuses (columns)
-- ============================================================================
-- Makes the agent task board's columns user-configurable. Until now the seven
-- statuses (pending, in_progress, in_review, completed, failed, cancelled,
-- deleted) were a fixed enum hard-wired both in a CHECK constraint and across
-- the frontend. This table turns each column into a row a user can rename,
-- reorder, recolour, hide, or cap with a WIP limit, plus lets them add brand
-- new custom columns.
--
-- Each status maps to a canonical lifecycle CATEGORY. The agent state machine
-- (claim/complete/review/cancel) keys off the category, never the literal key,
-- so a board can hold any number of custom statuses while the worker/reviewer
-- loop still knows what "in review" or "done" means. The seven defaults are
-- materialised per board on first access (see TaskStatusService.ensureDefaults)
-- with their historical keys, so an un-customised board behaves exactly as
-- before.
--
-- Scope mirrors agent_tasks: (tenant_id, organization_id) where a NULL
-- organization_id is the personal workspace.
-- ============================================================================

SET search_path TO agent;

CREATE TABLE IF NOT EXISTS agent.task_statuses (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        TEXT NOT NULL,
    -- NULL = personal scope (matches agent_tasks.organization_id semantics)
    organization_id  TEXT,
    -- Stable machine key referenced by agent_tasks.status. Lowercase snake_case.
    key              VARCHAR(40) NOT NULL,
    -- Human display label shown on the column header.
    label            VARCHAR(60) NOT NULL,
    -- Canonical lifecycle role the agent state machine reasons about.
    category         VARCHAR(20) NOT NULL,
    -- Column order on the board, ascending.
    position         INT NOT NULL DEFAULT 0,
    -- Optional colour token (tailwind class or hex). NULL = category default.
    color            VARCHAR(30),
    -- Work-in-progress limit (F3). NULL = no limit. <=0 is rejected in code.
    wip_limit        INT,
    -- TRUE for the seven seeded defaults: they may be renamed / reordered /
    -- recoloured / hidden / WIP-capped but never deleted, guaranteeing the
    -- agent lifecycle always has a target status for every required category.
    is_system        BOOLEAN NOT NULL DEFAULT FALSE,
    -- Hidden columns are collapsed on the board by default (failed / cancelled /
    -- deleted ship hidden, matching the previous hard-coded default).
    hidden           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT task_statuses_category_chk CHECK (category IN
        ('pending','in_progress','in_review','done','failed','cancelled','deleted')),
    CONSTRAINT task_statuses_wip_chk CHECK (wip_limit IS NULL OR wip_limit > 0)
);

-- One status key per board. COALESCE folds the NULL personal scope into a real
-- value so (tenant, NULL, 'pending') collides as expected (plain UNIQUE would
-- treat every NULL org as distinct and allow duplicate keys in personal scope).
CREATE UNIQUE INDEX IF NOT EXISTS uq_task_statuses_board_key
    ON agent.task_statuses (tenant_id, COALESCE(organization_id, ''), key);

-- Board fan-out: list a board's columns in display order.
CREATE INDEX IF NOT EXISTS idx_task_statuses_board
    ON agent.task_statuses (tenant_id, COALESCE(organization_id, ''), position);
