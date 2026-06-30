-- Update find_rows node_type_documentation: no longer split-like, returns items[] array
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  description = 'Query a data table and return matching rows as an items[] array. This is a simple collection node - it does NOT split/spawn parallel contexts. To iterate per-row, connect a Split node after this node.',
  parameters = '{
    "where": {"type": "object", "required": false, "description": "Filter condition: {column, operator, value}"},
    "limit": {"type": "number", "default": 100, "required": false, "description": "Maximum number of rows to return (safety cap)"},
    "offset": {"type": "number", "default": 0, "required": false, "description": "Starting position for pagination"}
  }'::jsonb,
  outputs = '{
    "items": {"type": "array", "description": "All found rows (after limit applied). Use a Split node to iterate."},
    "item_count": {"type": "number", "description": "Number of items found"},
    "total_before_limit": {"type": "number", "description": "Total rows before maxItems cap"},
    "has_more": {"type": "boolean", "description": "Whether more rows exist beyond the limit"},
    "exit_reason": {"type": "text", "description": "items_found or empty_result"},
    "find_id": {"type": "text", "description": "Node ID of the find node"},
    "max_items": {"type": "number", "description": "Configured max items cap"}
  }'::jsonb,
  concepts = '["Returns items[] array - does NOT split/spawn parallel contexts", "Connect a Split node after to iterate per-row", "Use {{table:label.output.items}} to access the full array", "Use {{table:label.output.item_count}} for the count", "limit/offset control pagination at query level"]'::jsonb,
  examples = '["workflow_builder(action=''add_node'', type=''find_rows'', label=''Find Users'', params={where: {column: ''status'', operator: ''='', value: ''active''}, limit: 50}, connect_after=''Start'')", "Pattern: Find Rows -> Split -> Process -> Merge"]'::jsonb,
  updated_at = NOW()
WHERE type = 'find_rows';
