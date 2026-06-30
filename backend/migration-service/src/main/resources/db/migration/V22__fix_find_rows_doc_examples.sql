-- ============================================================================
-- V22: Fix ALL node_type_documentation references to old tool name
-- ============================================================================
-- The tool was renamed from 'workflow_builder' to 'workflow' but the
-- node_type_documentation examples/concepts were never updated.
-- This migration does a global text replace across ALL JSONB fields.
-- Also updates table CRUD examples to use direct action syntax.
-- ============================================================================

SET search_path TO orchestrator;

-- ============================================================================
-- 1. Global rename: workflow_builder( → workflow( in ALL JSONB text fields
-- ============================================================================

UPDATE node_type_documentation
SET examples = replace(examples::text, 'workflow_builder(', 'workflow(')::jsonb,
    updated_at = NOW()
WHERE examples::text LIKE '%workflow_builder(%';

UPDATE node_type_documentation
SET concepts = replace(concepts::text, 'workflow_builder(', 'workflow(')::jsonb,
    updated_at = NOW()
WHERE concepts::text LIKE '%workflow_builder(%';

UPDATE node_type_documentation
SET description = replace(description, 'workflow_builder(', 'workflow('),
    updated_at = NOW()
WHERE description LIKE '%workflow_builder(%';

-- ============================================================================
-- 2. Table CRUD: update examples to use direct action syntax with table_id
--    (find_rows, insert_row, read_rows, update_row, delete_row are PRIMARY
--     actions - not add_node subtypes)
-- ============================================================================

UPDATE node_type_documentation
SET examples = '[
    "workflow(action=''find_rows'', label=''Find Users'', table_id=123, where={column: ''status'', operator: ''='', value: ''active''}, limit: 50, connect_after=''Start'')",
    "Pattern: Find Rows -> Split -> Process -> Merge"
  ]'::jsonb,
    updated_at = NOW()
WHERE type = 'find_rows';

UPDATE node_type_documentation
SET examples = '[
    "workflow(action=''insert_row'', label=''Save User'', table_id=123, columns={name: ''John'', email: ''john@test.com''}, connect_after=''Start'')"
  ]'::jsonb,
    updated_at = NOW()
WHERE type = 'insert_row';

UPDATE node_type_documentation
SET examples = '[
    "workflow(action=''read_rows'', label=''Fetch Users'', table_id=123, where={column: ''status'', operator: ''='', value: ''active''}, limit: 50, connect_after=''Start'')"
  ]'::jsonb,
    updated_at = NOW()
WHERE type = 'get_rows';

UPDATE node_type_documentation
SET examples = '[
    "workflow(action=''update_row'', label=''Update Status'', table_id=123, where={column: ''id'', operator: ''='', value: ''123''}, set={status: ''done''}, connect_after=''...'')"
  ]'::jsonb,
    updated_at = NOW()
WHERE type = 'update_row';

UPDATE node_type_documentation
SET examples = '[
    "workflow(action=''delete_row'', label=''Remove Old'', table_id=123, where={column: ''created_at'', operator: ''<'', value: ''2024-01-01''}, connect_after=''...'')"
  ]'::jsonb,
    updated_at = NOW()
WHERE type = 'delete_row';
