-- Update node_type_documentation for Filter, Sort, Limit, RemoveDuplicates, Summarize
-- All 5 nodes now require an explicit 'input' field (no more auto-detect from context)

SET search_path TO orchestrator;

-- Filter
UPDATE node_type_documentation
SET
  description = 'Filter items from an explicit input array based on conditions. Returns matched and rejected items. Input is required - specify the array to filter.',
  parameters = '{
    "input": {"type": "string", "required": true, "description": "Expression referencing the items array to filter, e.g. {{core:step.output.items}}"},
    "conditions": {"type": "array", "required": true, "description": "Array of {field, operator, value} conditions. Operators: equals, notEquals, contains, notContains, greaterThan, lessThan, greaterOrEqual, lessOrEqual, startsWith, endsWith, isEmpty, isNotEmpty"},
    "mode": {"type": "string", "default": "and", "description": "Combine conditions with and/or"}
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'filter';

-- Sort
UPDATE node_type_documentation
SET
  description = 'Sort items from an explicit input array by one or more fields. Input is required - specify the array to sort.',
  parameters = '{
    "input": {"type": "string", "required": true, "description": "Expression referencing the items array to sort, e.g. {{core:step.output.items}}"},
    "fields": {"type": "array", "required": true, "description": "Array of {field, direction} sort specifications. direction: asc or desc"}
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'sort';

-- Limit
UPDATE node_type_documentation
SET
  description = 'Take the first or last N items from an explicit input array. Input is required - specify the array to limit.',
  parameters = '{
    "input": {"type": "string", "required": true, "description": "Expression referencing the items array to limit, e.g. {{core:step.output.items}}"},
    "count": {"type": "number", "required": true, "description": "Number of items to keep"},
    "from": {"type": "string", "default": "first", "description": "Take from first or last"},
    "offset": {"type": "number", "default": 0, "description": "Skip N items before taking"}
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'limit';

-- Remove Duplicates
UPDATE node_type_documentation
SET
  description = 'Remove duplicate items from an explicit input array based on field comparison. Input is required - specify the array to deduplicate.',
  parameters = '{
    "input": {"type": "string", "required": true, "description": "Expression referencing the items array to deduplicate, e.g. {{core:step.output.items}}"},
    "fields": {"type": "array", "required": false, "description": "Fields to compare for duplicates. Empty = compare all fields"},
    "keep": {"type": "string", "default": "first", "description": "Which duplicate to keep: first or last"}
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'remove_duplicates';

-- Summarize
UPDATE node_type_documentation
SET
  description = 'Compute aggregate values (sum, avg, count, countDistinct, min, max, concatenate) from an explicit input array. Input is required - specify the array to summarize.',
  parameters = '{
    "input": {"type": "string", "required": true, "description": "Expression referencing the items array to summarize, e.g. {{core:step.output.items}}"},
    "aggregations": {"type": "array", "required": true, "description": "Array of {field, operation, alias}. Operations: sum, avg, count, countDistinct, min, max, concatenate"},
    "groupBy": {"type": "array", "required": false, "description": "Fields to group by before aggregating"}
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'summarize';
