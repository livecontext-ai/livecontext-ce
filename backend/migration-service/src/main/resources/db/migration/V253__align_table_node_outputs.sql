-- Align table CRUD node_type_documentation.outputs with NodeSpec / persisted output keys.
-- These fields are what agents can reference with {{table:<label>.output.<field>}}.
--
-- V253 collision note (2026-05-19): a sibling migration from branch
-- `worktree-org-scope-consolidation` (V253__webhook_tokens_tenant_id.sql) also
-- claimed slot V253 after the merge c4fe1630b. This file kept V253 because it
-- was already applied to dev databases; the webhook one was renumbered to V258.
-- V252 is intentionally skipped (removed during the CE LLM proxy decommission).

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET outputs = '{
  "operation":       {"type": "string",   "description": "CRUD operation name"},
  "success":         {"type": "boolean",  "description": "Whether the operation succeeded"},
  "message":         {"type": "string",   "description": "Human-readable result message"},
  "row_id":          {"type": "string",   "description": "ID of the inserted row"},
  "created_at":      {"type": "datetime", "description": "ISO timestamp when the row was created"},
  "inserted_count":  {"type": "number",   "description": "Number of rows inserted"},
  "inserted_values": {"type": "object",   "description": "Values that were inserted"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'insert_row';

UPDATE node_type_documentation
SET outputs = '{
  "operation": {"type": "string",  "description": "CRUD operation name"},
  "success":   {"type": "boolean", "description": "Whether the operation succeeded"},
  "message":   {"type": "string",  "description": "Human-readable result message"},
  "rows":      {"type": "array",   "description": "Retrieved rows"},
  "row_count": {"type": "number",  "description": "Number of rows returned"},
  "rowCount":  {"type": "number",  "description": "CamelCase alias for row_count"},
  "has_more":  {"type": "boolean", "description": "Whether more rows are available"},
  "offset":    {"type": "number",  "description": "Pagination offset used"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'get_rows';

UPDATE node_type_documentation
SET outputs = '{
  "operation":     {"type": "string",   "description": "CRUD operation name"},
  "success":       {"type": "boolean",  "description": "Whether the operation succeeded"},
  "message":       {"type": "string",   "description": "Human-readable result message"},
  "updated_count": {"type": "number",   "description": "Number of rows updated"},
  "rows_affected": {"type": "number",   "description": "Number of rows affected"},
  "updated_at":    {"type": "datetime", "description": "ISO timestamp when the update was performed"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'update_row';

UPDATE node_type_documentation
SET outputs = '{
  "operation":     {"type": "string",   "description": "CRUD operation name"},
  "success":       {"type": "boolean",  "description": "Whether the operation succeeded"},
  "message":       {"type": "string",   "description": "Human-readable result message"},
  "deleted_count": {"type": "number",   "description": "Number of rows deleted"},
  "rows_affected": {"type": "number",   "description": "Number of rows affected"},
  "deleted_at":    {"type": "datetime", "description": "ISO timestamp when the deletion was performed"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'delete_row';

UPDATE node_type_documentation
SET outputs = '{
  "operation":      {"type": "string",  "description": "CRUD operation name"},
  "success":        {"type": "boolean", "description": "Whether the operation succeeded"},
  "message":        {"type": "string",  "description": "Human-readable result message"},
  "createdColumns": {"type": "array",   "description": "List of columns that were created"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'create_column';
