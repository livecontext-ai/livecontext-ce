-- ============================================================================
-- V353: Task estimation + time tracking
-- ============================================================================
-- Adds an estimate and logged time (both in minutes) so a board can show
-- per-card effort and roll up per-column totals. Both nullable: NULL = not set.
-- ============================================================================

SET search_path TO agent;

ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS estimate_minutes   INT,
    ADD COLUMN IF NOT EXISTS time_spent_minutes INT;
