-- V92: Fix node_type_documentation for the 'filter' node outputs.
--
-- Bug: FilterNode.execute() emits items, rejected_items, count, rejected_count,
--      original_count, matched, filter_mode, conditions_evaluated, and data.
--      However V11 seeded only {matched, filter_mode, conditions_evaluated, data}.
--      As a result:
--        1. GenericOutputSchemaMapper (backed by FilterNodeSpec) stripped the
--           extra fields before persistence until FilterNodeSpec was expanded.
--        2. LLM agents calling node_library to learn Filter outputs only saw
--           `data` and `matched`, and never wrote templates like
--           {{core:label.output.items}} or {{core:label.output.count}}.
--
-- This migration aligns node_type_documentation.outputs with the full set of
-- fields emitted by FilterNode (and now declared by FilterNodeSpec).

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET outputs = '{
    "matched": {"type": "boolean", "description": "Whether any item passed the filter conditions"},
    "items": {"type": "array", "description": "Items that passed the filter conditions"},
    "rejected_items": {"type": "array", "description": "Items that did not pass the filter conditions"},
    "count": {"type": "number", "description": "Number of items that passed the filter"},
    "rejected_count": {"type": "number", "description": "Number of items that did not pass the filter"},
    "original_count": {"type": "number", "description": "Number of items in the resolved input"},
    "filter_mode": {"type": "string", "description": "Filter mode used for evaluation (and/or)"},
    "conditions_evaluated": {"type": "number", "description": "Number of conditions evaluated"},
    "data": {"type": "object", "description": "Original input data (only present when matched is true)"}
}'::jsonb,
    examples = '["Use AND mode when ALL conditions must be true to keep an item", "Use OR mode when ANY condition being true is sufficient to keep an item", "isEmpty and isNotEmpty operators do not require a value parameter", "Numeric comparisons (greaterThan, lessThan, etc.) attempt numeric parsing, fallback to string comparison", "Access filter result: {{core:label.output.matched}} (boolean)", "Access kept items: {{core:label.output.items}} (array)", "Access rejected items: {{core:label.output.rejected_items}} (array)", "Access count: {{core:label.output.count}} (number)", "Chain with Split to iterate filtered items: Filter -> Split(input={{core:filter_label.output.items}})"]'::jsonb,
    updated_at = NOW()
WHERE type = 'filter';
