-- ============================================================================
-- V355: Task checklists + attachments
-- ============================================================================
-- Lightweight, board-friendly extras stored inline as JSONB arrays on the task
-- (same pattern as label_ids / blocked_by_ids), so the board renders progress
-- and attachment chips with no extra query.
--
--   checklist   = [{ id, text, done }]                       (acceptance items)
--   attachments = [{ id, fileName, storageKey, mimeType, sizeBytes }]
--                 storageKey points at a file already uploaded through the
--                 normal storage flow; the task row only records the link.
-- ============================================================================

SET search_path TO agent;

ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS checklist   JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS attachments JSONB NOT NULL DEFAULT '[]'::jsonb;
