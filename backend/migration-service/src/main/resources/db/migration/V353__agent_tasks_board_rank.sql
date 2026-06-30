-- ============================================================================
-- V351: Manual card ordering within a board column
-- ============================================================================
-- Adds board_rank so a user can drag cards into an explicit order inside a
-- column (the core Kanban gesture). NULL = unranked (sorts after ranked cards,
-- falling back to the existing recency order). Ranks are assigned sequentially
-- per reorder and only ever compared WITHIN a column (the board groups by status
-- first, then orders by rank), so cross-column rank collisions are harmless.
-- ============================================================================

SET search_path TO agent;

ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS board_rank DOUBLE PRECISION;
