-- ============================================================================
-- V95: Event-driven datasource trigger
--   1. Create trigger.datasource_trigger_subscriptions (registry for row-event
--      subscriptions, synced from workflow plan at pin/save time).
--   2. Rewrite node_type_documentation for type='table' to describe the
--      event-driven behavior (row_created/row_updated/row_deleted) instead of
--      the old on-demand loader.
-- ============================================================================

CREATE TABLE IF NOT EXISTS "trigger".datasource_trigger_subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    workflow_id     UUID NOT NULL,
    plan_version    INTEGER NOT NULL,
    trigger_id      TEXT NOT NULL,
    data_source_id  BIGINT NOT NULL,
    tenant_id       TEXT NOT NULL,
    event_types     JSONB NOT NULL,
    filter_json     JSONB,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ds_trigger_sub_workflow_trigger UNIQUE (workflow_id, trigger_id)
);

CREATE INDEX IF NOT EXISTS idx_ds_trigger_sub_lookup
    ON "trigger".datasource_trigger_subscriptions (data_source_id, is_active);

CREATE INDEX IF NOT EXISTS idx_ds_trigger_sub_workflow
    ON "trigger".datasource_trigger_subscriptions (workflow_id);

CREATE INDEX IF NOT EXISTS idx_ds_trigger_sub_tenant
    ON "trigger".datasource_trigger_subscriptions (tenant_id);

-- ============================================================================
-- Rewrite node_type_documentation for the event-driven datasource trigger.
-- The agent-facing docs must only describe actions the LLM can take via MCP.
-- ============================================================================

UPDATE node_type_documentation
SET
    description = 'Event-driven trigger. Fires one workflow run for each row created, updated or deleted in a datasource. Use this when you want the workflow to react to row changes (payment received, user signed up, record archived). For bulk scans of an entire table, use schedule + find_rows instead.',
    parameters = '{
        "table_id": {"type": "integer", "required": true, "description": "ID of the datasource to subscribe to. Use table(action=''list'') to find available datasources."},
        "event_types": {"type": "array", "items": {"type": "string", "enum": ["row_created", "row_updated", "row_deleted"]}, "required": true, "default": ["row_created"], "description": "Which row events fire this trigger. Multi-select."},
        "filter": {"type": "object", "required": false, "description": "Optional single-condition filter {column, operator, value}. Trigger does not fire if filter is set and does not match. Operators: =, !=, >, >=, <, <=, in, not_in, contains, starts_with, ends_with, is_null, is_not_null. For in/not_in, value is a list (or comma-separated string). is_null/is_not_null take no value.", "example": {"column": "status", "operator": "=", "value": "paid"}}
    }'::jsonb,
    outputs = '{
        "row": {"type": "object", "description": "The row that triggered the event. Current state for row_created/row_updated; last-known state for row_deleted."},
        "previous_row": {"type": "object", "description": "Pre-change row. Populated only for row_updated. null for row_created and row_deleted."},
        "event_type": {"type": "string", "enum": ["row_created", "row_updated", "row_deleted"], "description": "Which event fired this run."},
        "row_id": {"type": "number", "description": "ID of the row in the datasource."},
        "datasource_id": {"type": "number", "description": "ID of the datasource emitting the event."},
        "triggered_at": {"type": "string", "description": "ISO timestamp of the event (after DB commit)."},
        "<column_name>": {"type": "varies", "description": "Row columns are also flattened at top level for easy access ({{trigger.column_name}})."}
    }'::jsonb,
    concepts = '[
        "One event = one workflow run. Parallel inserts spawn parallel runs (one per row).",
        "row_created fires after a row is persisted (post-commit). previous_row is null.",
        "row_updated fires after any column changes; previous_row holds the pre-change row.",
        "row_deleted fires after a row is removed; row holds its last-known state.",
        "The optional filter is applied before firing - a non-matching event costs nothing.",
        "Only fires on the pinned workflow version in production, same as schedule and webhook triggers.",
        "For bulk processing existing rows (not reacting to changes), use schedule trigger + find_rows."
    ]'::jsonb,
    examples = '[
        "workflow_builder(action=''add_node'', type=''table'', label=''On new user'', params={table_id: 12, event_types: [''row_created'']})",
        "workflow_builder(action=''add_node'', type=''table'', label=''On payment confirmed'', params={table_id: 34, event_types: [''row_updated''], filter: {column: ''status'', operator: ''='', value: ''paid''}})",
        "workflow_builder(action=''add_node'', type=''table'', label=''On row removed'', params={table_id: 7, event_types: [''row_deleted'']})"
    ]'::jsonb,
    keywords = '["table","datasource","trigger","row","event","created","updated","deleted","change","insert","reactive"]'::jsonb,
    updated_at = NOW()
WHERE type = 'table';
