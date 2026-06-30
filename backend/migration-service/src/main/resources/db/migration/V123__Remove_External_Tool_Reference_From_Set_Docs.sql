-- ============================================================================
-- V123: Strip third-party tool references from the Set node documentation
-- seeded by V53 / V55. The live docs are consumed by the LLM and the
-- inspector tooltip, so we keep them product-neutral.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET description = 'Assign or transform fields on the input data - the most-used no-code field assignment node. Each assignment has a name (output key), a value (template string resolved via SpEL), and an optional type (string|number|boolean|json|auto). When keepOnlySet=false the assignments are merged onto the upstream input data; when true only the assigned fields are returned. When emitting via set_plan, wrap the config under ''set'': { assignments, keepOnlySet, input }.',
    keywords = '["set", "edit", "assign", "field", "transform"]'::jsonb,
    updated_at = NOW()
WHERE type = 'set';
