-- ============================================================================
-- V81: Add node_type_documentation row for core:task node
--   - core:task - CRUD operations on agent tasks from within a workflow
-- ============================================================================

SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'task',
    'Task',
    'core',
    'core',
    'CRUD operations on agent tasks directly from a workflow, without going through an AI agent. Supports five operations: create_task, get_task, update_task, delete_task, list_tasks. Calls agent-service internally. When emitting via set_plan, wrap the config under ''task'': { operation, taskId, title, instructions, priority, agentId, reviewerAgentId, status, search, limit }.',
    '{
      "operation":       {"type": "string", "required": true, "description": "One of: create_task, get_task, update_task, delete_task, list_tasks"},
      "taskId":          {"type": "string", "required": false, "description": "UUID of the task (required for get_task, update_task, delete_task). Supports templates e.g. {{mcp:step.output.id}}"},
      "title":           {"type": "string", "required": false, "description": "Task title (required for create_task, optional for update_task). Supports templates."},
      "instructions":    {"type": "string", "required": false, "description": "Task instructions/description. Supports templates."},
      "priority":        {"type": "string", "required": false, "description": "One of: low, normal, high, urgent"},
      "agentId":         {"type": "string", "required": false, "description": "UUID of the agent to assign the task to. Supports templates."},
      "reviewerAgentId": {"type": "string", "required": false, "description": "UUID of the reviewer agent. Supports templates."},
      "status":          {"type": "string", "required": false, "description": "Status filter for list_tasks or new status for update_task"},
      "search":          {"type": "string", "required": false, "description": "Search term for list_tasks. Supports templates."},
      "limit":           {"type": "number", "required": false, "default": 50, "description": "Max results for list_tasks (1-200)"}
    }'::jsonb,
    '{
      "node_type":  {"type": "string",  "description": "Always ''TASK''"},
      "operation":  {"type": "string",  "description": "The operation that was executed (create_task, get_task, update_task, delete_task, list_tasks)"},
      "success":    {"type": "boolean", "description": "Whether the operation succeeded"},
      "task":       {"type": "object",  "description": "The task object (for create_task, get_task, update_task). Contains id, title, instructions, status, priority, assignedTo, etc."},
      "task_id":    {"type": "string",  "description": "The deleted task ID (for delete_task only)"},
      "tasks":      {"type": "array",   "description": "Array of task objects (for list_tasks only)"},
      "count":      {"type": "number",  "description": "Number of tasks in current page (for list_tasks only)"},
      "total":      {"type": "number",  "description": "Total matching tasks (for list_tasks only)"}
    }'::jsonb,
    NULL,
    NULL,
    '[]'::jsonb,
    '[]'::jsonb,
    '["task", "crud", "create", "delete", "update", "list", "agent", "assign", "delegation"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label,
    category = EXCLUDED.category,
    variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description,
    parameters = EXCLUDED.parameters,
    outputs = EXCLUDED.outputs,
    keywords = EXCLUDED.keywords,
    updated_at = NOW();
