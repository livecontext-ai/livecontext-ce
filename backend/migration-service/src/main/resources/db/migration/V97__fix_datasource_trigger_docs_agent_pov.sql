-- ============================================================================
-- V97: Fix node_type_documentation for type='table' - agent-POV coherence.
--
-- Drifts corrected against actual trigger behavior (audit 2026-04-17):
--   1. event_types.required was 'true' but TriggerCreator.normalizeEventTypes()
--      defaults to all three events when omitted → switch to required=false and
--      default=['row_created','row_updated','row_deleted'] so the agent can
--      safely omit it and still get the documented behavior.
--   2. outputs referenced "{{trigger.column_name}}" - real reference syntax is
--      "{{trigger:<label>.output.<column>}}". Fixed.
--   3. examples invoked a non-existent tool "workflow_builder(...)". The actual
--      MCP tool is "workflow(...)" (with action='add_node'). Fixed.
-- ============================================================================

UPDATE node_type_documentation
SET
    parameters = '{
        "table_id": {"type": "integer", "required": true, "description": "ID of the datasource to subscribe to. Use table(action=''list'') to find available datasources."},
        "event_types": {"type": "array", "items": {"type": "string", "enum": ["row_created", "row_updated", "row_deleted"]}, "required": false, "default": ["row_created", "row_updated", "row_deleted"], "description": "Which row events fire this trigger. Multi-select. Omit to subscribe to all three."},
        "filter": {"type": "object", "required": false, "description": "Optional single-condition filter {column, operator, value}. Trigger does not fire if filter is set and does not match. Operators: =, !=, >, >=, <, <=, in, not_in, contains, starts_with, ends_with, is_null, is_not_null. For in/not_in, value is a list (or comma-separated string). is_null/is_not_null take no value.", "example": {"column": "status", "operator": "=", "value": "paid"}}
    }'::jsonb,
    outputs = '{
        "row": {"type": "object", "description": "The row that triggered the event. Current state for row_created/row_updated; last-known state for row_deleted."},
        "previous_row": {"type": "object", "description": "Pre-change row. Populated only for row_updated. null for row_created and row_deleted."},
        "event_type": {"type": "string", "enum": ["row_created", "row_updated", "row_deleted"], "description": "Which event fired this run."},
        "row_id": {"type": "number", "description": "ID of the row in the datasource."},
        "datasource_id": {"type": "number", "description": "ID of the datasource emitting the event."},
        "triggered_at": {"type": "string", "description": "ISO timestamp of the event (after DB commit)."},
        "<column_name>": {"type": "varies", "description": "Each row column is also flattened at the top level. Reference with {{trigger:<label>.output.<column>}} (for a trigger labelled ''On Row Change'', use {{trigger:on_row_change.output.amount}})."}
    }'::jsonb,
    concepts = '[
        "One event = one workflow run. Parallel inserts spawn parallel runs (one per row).",
        "row_created fires after a row is persisted (post-commit). previous_row is null.",
        "row_updated fires after any column changes; previous_row holds the pre-change row.",
        "row_deleted fires after a row is removed; row holds its last-known state; previous_row is null.",
        "The optional filter is applied before firing - a non-matching event costs nothing.",
        "Only fires on the pinned workflow version in production, same as schedule and webhook triggers. Unpin → no runs.",
        "For bulk processing existing rows (not reacting to changes), use schedule trigger + find_rows."
    ]'::jsonb,
    examples = '[
        "workflow(action=''add_node'', type=''table'', label=''On new user'', params={table_id: 12, event_types: [''row_created'']})",
        "workflow(action=''add_node'', type=''table'', label=''On payment confirmed'', params={table_id: 34, event_types: [''row_updated''], filter: {column: ''status'', operator: ''='', value: ''paid''}})",
        "workflow(action=''add_node'', type=''table'', label=''On row removed'', params={table_id: 7, event_types: [''row_deleted'']})",
        "workflow(action=''add_node'', type=''table'', label=''On any change'', params={table_id: 42})  // omit event_types → all three"
    ]'::jsonb,
    updated_at = NOW()
WHERE type = 'table';
