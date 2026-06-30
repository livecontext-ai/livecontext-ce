-- ============================================================================
-- V124: Table (datasource) trigger docs - promote safe nested .row.<col> path,
--       expose batch-scan keys, document split chain pattern.
--
-- Bug: V97 told agents to write {{trigger:<label>.output.<column>}}, but column
-- names that collide with LEGACY_RESERVED_KEYS (status, count, data, source,
-- error, message, offset, limit, hasMore, totalCount, realTotalCount,
-- nextOffset, strategy, maxItemsCap, maxItemsReached, _inputs, triggerId,
-- tenantId - see TriggerPayloadBuilder.LEGACY_RESERVED_KEYS) are silently
-- shadowed by payload metadata. Agent writes a reference, gets null/wrong
-- value, no error raised.
--
-- Fix: agents are now told to use the always-safe nested form
-- {{trigger:<label>.output.row.<column>}}. Also documents:
--   - the batch-scan testing shape ({data:[{id, data:{...cols}}], count, ...})
--     emitted by workflow(action='execute') - same shape as find_rows.items
--   - chain core:split with input={{trigger:<label>.output.data}} for per-row
--     processing of the batch (mirrors find_rows → split pattern)
--
-- Aligned with: TriggerCreator.buildTriggerSchema, TriggerStepResponseBuilder,
--               WorkflowBuilderHelpModule.add_table_trigger,
--               WorkflowHelpProvider.fireableTypes['datasource'].
-- ============================================================================

UPDATE orchestrator.node_type_documentation
SET
    outputs = '{
        "row": {"type": "object", "description": "The row that triggered the event. Read columns via {{trigger:<label>.output.row.<column>}} - always safe (no collision with reserved payload keys). Current state for row_created/row_updated; last-known state for row_deleted."},
        "previous_row": {"type": "object", "description": "Pre-change row. Populated only for row_updated. null for row_created and row_deleted."},
        "event_type": {"type": "string", "enum": ["row_created", "row_updated", "row_deleted"], "description": "Which event fired this run."},
        "row_id": {"type": "number", "description": "ID of the row in the datasource."},
        "datasource_id": {"type": "number", "description": "ID of the datasource emitting the event."},
        "triggered_at": {"type": "string", "description": "ISO timestamp of the event (after DB commit)."},
        "data": {"type": "array", "description": "Batch-scan only - list of {id, data:{...columns}} like find_rows.items. Emitted when fired via workflow(action=''execute'') for testing. Chain core:split with input={{trigger:<label>.output.data}} for per-row processing - read columns via {{item.data.<column>}}."},
        "count": {"type": "number", "description": "Batch-scan only - number of rows returned by the test fire."}
    }'::jsonb,
    concepts = '[
        "One event = one workflow run. Parallel inserts spawn parallel runs (one per row).",
        "Per-column access: ALWAYS use {{trigger:<label>.output.row.<column>}} - the safe nested path. Never use the flat top-level form: column names that collide with reserved payload keys (status, count, data, source, error, message, offset, limit, hasMore, totalCount, realTotalCount, nextOffset, strategy, maxItemsCap, maxItemsReached, _inputs, triggerId, tenantId) are silently shadowed.",
        "Two test paths: (1) workflow(action=''execute'') runs the batch-scan loader → output emits {data:[{id, data:{...cols}}, ...], count, hasMore} - same shape as find_rows.items. Chain core:split with input={{trigger:<label>.output.data}} and read columns via {{item.data.<column>}}. (2) For real event-driven testing, cause a row change: table(action=''insert_rows''|''update_rows''|''delete_rows'', table_id=<id>, ...) - output then carries event_type/row_id/row/previous_row.",
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
        "workflow(action=''add_node'', type=''table'', label=''On any change'', params={table_id: 42})  // omit event_types → all three",
        "// Per-row processing of the batch-scan test fire (same pattern as find_rows → split):",
        "// 1) workflow(action=''add_node'', type=''table'', label=''On Row'', params={table_id: 42})",
        "// 2) workflow(action=''add_node'', type=''split'', label=''Each Row'', params={items: ''{{trigger:on_row.output.data}}''}, connect_after=''On Row'')",
        "// 3) workflow(action=''add_node'', type=''set'', label=''Read'', params={assignments: [{name: ''status'', value: ''{{core:each_row.output.current_item.data.status}}''}]}, connect_after=''Each Row'')"
    ]'::jsonb,
    updated_at = NOW()
WHERE type = 'table';
