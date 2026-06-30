-- V50: Fix node_type_documentation for 'filter' and 'sort' rows.
--
-- Bug 1: The 'sort' row had parameters = NULL, so an LLM reading
--        nodeLibraryService.findByType('sort') saw zero declared parameters.
--        SortNode.execute() requires both 'input' (SpEL expression) and
--        'fields' (list of {field, direction}).
--
-- Bug 2: The 'filter' row declared 'conditions' and 'mode' but omitted
--        'input' - yet FilterNode.execute() explicitly requires 'input'
--        as a SpEL expression (see FilterNode.java:50-53).
--
-- V11 is already applied in production, so we patch via UPDATE rather than
-- modifying V11 retroactively. Field-name canonicals are taken from the
-- executors (FilterNode/SortNode) and UtilityNodeCreator.

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = '{"input": {"type": "string", "required": true, "description": "SpEL expression for the items to filter, e.g. {{core:step.output.items}} or {{mcp:api.output.data}}. Must resolve to an array (or an object treated as a single item)."}, "mode": {"enum": ["and", "or"], "type": "string", "default": "and", "required": false, "description": "Filter mode: ''and'' (all conditions must match) or ''or'' (any condition must match)."}, "conditions": {"type": "array", "items": {"field": "string", "value": "string", "operator": "string (equals|notEquals|contains|notContains|greaterThan|lessThan|greaterOrEqual|lessOrEqual|startsWith|endsWith|isEmpty|isNotEmpty)"}, "required": true, "description": "List of filter conditions. Each condition has: field (column name), operator (comparison type), value (expected value)."}}'::jsonb,
    updated_at = NOW()
WHERE type = 'filter';

UPDATE node_type_documentation
SET parameters = '{"input": {"type": "string", "required": true, "description": "SpEL expression for the items to sort, e.g. {{core:step.output.items}} or {{mcp:api.output.rows}}. Must resolve to an array of objects."}, "fields": {"type": "array", "items": {"field": "string (column name to sort by)", "direction": "string (asc|desc, default asc)"}, "required": true, "description": "Ordered list of sort fields. Multi-field sorting is applied left-to-right (first field is primary)."}}'::jsonb,
    updated_at = NOW()
WHERE type = 'sort';
