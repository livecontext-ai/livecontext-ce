-- V98: Generic resource publication support (TABLE, INTERFACE, SKILL)
-- Extends workflow_publications so any resource type can be published/acquired
-- through the same entity and marketplace, reusing planSnapshot as the generic
-- snapshot container (interpretation is driven by publication_type).
--
-- publication_type and display_mode are VARCHAR(50) with Java enum @Enumerated(STRING),
-- so new enum values require no column alteration - only the Java enum changes.

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS resource_id VARCHAR(255);

-- At most one ACTIVE publication per (publication_type, resource_id) pair, excluding
-- the legacy WORKFLOW/AGENT types that continue to use workflow_id / agent_config_id.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pub_type_resource
    ON publication.workflow_publications(publication_type, resource_id)
    WHERE resource_id IS NOT NULL
      AND publication_type IN ('TABLE', 'INTERFACE', 'SKILL');

CREATE INDEX IF NOT EXISTS idx_pub_resource_type
    ON publication.workflow_publications(publication_type)
    WHERE publication_type IN ('TABLE', 'INTERFACE', 'SKILL');
