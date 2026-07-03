-- ============================================================================
-- V380: Align task node output documentation with TaskNode runtime output
--   - TaskNode emits resolved_params (snapshot of resolved request params) on
--     EVERY result, success and failure alike, but node_type_documentation
--     never listed it (V81/V292 outputs stop at total). Add it so agents see
--     the full contract.
--   - Also lowercase the status mention in the task field description
--     (backend statuses are lowercase strings: pending, in_progress,
--     in_review, completed, failed, cancelled).
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
    outputs = '{
      "node_type":       {"type": "string",  "description": "Always ''TASK''"},
      "operation":       {"type": "string",  "description": "The operation that was executed"},
      "success":         {"type": "boolean", "description": "Whether the operation succeeded"},
      "task":            {"type": "object",  "description": "The task object (create/get/update). Fields: id, title, instructions, status (starts pending if agentId set), priority, assignedTo, reviewerAgentId, createdAt, result (null until agent finishes)"},
      "task_id":         {"type": "string",  "description": "The deleted task ID (delete_task only)"},
      "tasks":           {"type": "array",   "description": "Array of task objects (list_tasks only)"},
      "count":           {"type": "number",  "description": "Number of tasks in current page (list_tasks only)"},
      "total":           {"type": "number",  "description": "Total matching tasks (list_tasks only)"},
      "resolved_params": {"type": "object",  "description": "Snapshot of the resolved request parameters (present on every result, including failures)"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'task';
