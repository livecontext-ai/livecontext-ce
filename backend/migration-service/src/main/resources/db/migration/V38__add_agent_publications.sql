-- V38: Add agent publication support to the marketplace
-- Extends workflow_publications to support AGENT publication type

ALTER TABLE publication.workflow_publications
  ADD COLUMN IF NOT EXISTS publication_type VARCHAR(30) NOT NULL DEFAULT 'WORKFLOW',
  ADD COLUMN IF NOT EXISTS agent_config_id UUID,
  ADD COLUMN IF NOT EXISTS agent_snapshot JSONB,
  ADD COLUMN IF NOT EXISTS agent_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS skill_count INTEGER NOT NULL DEFAULT 0;

-- Make workflow_id nullable (AGENT publications have no workflow)
ALTER TABLE publication.workflow_publications ALTER COLUMN workflow_id DROP NOT NULL;

-- Make plan_snapshot nullable (AGENT publications use agent_snapshot instead)
ALTER TABLE publication.workflow_publications ALTER COLUMN plan_snapshot DROP NOT NULL;

-- Replace unique constraint on workflow_id with partial unique indexes
-- Must use DROP CONSTRAINT (not DROP INDEX) since UNIQUE created a constraint + backing index
ALTER TABLE publication.workflow_publications DROP CONSTRAINT IF EXISTS workflow_publications_workflow_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pub_workflow_id ON publication.workflow_publications(workflow_id) WHERE workflow_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pub_agent_config_id ON publication.workflow_publications(agent_config_id) WHERE agent_config_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pub_type ON publication.workflow_publications(publication_type);
