-- V40: Add workflow_count to agent publications
-- Tracks the number of workflows bundled with an agent publication

ALTER TABLE publication.workflow_publications
  ADD COLUMN IF NOT EXISTS workflow_count INTEGER NOT NULL DEFAULT 0;
