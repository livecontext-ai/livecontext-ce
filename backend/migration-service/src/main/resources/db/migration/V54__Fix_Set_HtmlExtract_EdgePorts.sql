-- ============================================================================
-- V54: Hotfix for V53 - edge_ports column is a Map<String,Object> in the
-- NodeTypeDocumentationEntity, but V53 wrote a JSON array '[]' which caused
-- orchestrator-service to crash at boot during tools registration:
--   "Cannot deserialize value of type LinkedHashMap from Array value"
-- Reset the offending columns to NULL (the entity tolerates null cleanly).
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET edge_ports = NULL,
    global_variables = NULL,
    updated_at = NOW()
WHERE type IN ('set', 'html_extract');
