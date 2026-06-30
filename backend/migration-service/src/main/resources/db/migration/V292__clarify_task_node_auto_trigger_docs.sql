-- ============================================================================
-- V292: Clarify task node auto-trigger behavior in agent-facing documentation
--   - create_task with agentId auto-fires the agent ASYNCHRONOUSLY
--   - DAG does NOT block - returns immediately with PENDING status
--   - WARNING: do not chain task_create(agentId) + agent_node = double execution
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
    description = E'CRUD operations on agent tasks directly from a workflow. Supports five operations: create_task, get_task, update_task, delete_task, list_tasks.\n\n'
      || E'⚠ EXECUTION MODEL - READ CAREFULLY:\n'
      || E'• create_task with agentId AUTO-TRIGGERS the assigned agent ASYNCHRONOUSLY. The node returns IMMEDIATELY with status=PENDING - it does NOT wait for the agent to finish.\n'
      || E'• The agent result lands in the task record (task.result), NOT in the DAG $input. You cannot reference the agent''s work via {{core:<label>.output.task.result}} reliably because it may still be running.\n'
      || E'• NEVER chain task_create(agentId=X) → agent_node(agentId=X). This causes DOUBLE EXECUTION: the task auto-fires agent X in the background, then the agent node fires it again synchronously.\n\n'
      || E'WHEN TO USE EACH PATTERN:\n'
      || E'• Need agent result in the DAG (pass to next node, use in code/template): use an agent node directly - NO task node.\n'
      || E'• Need task board tracking only (fire-and-forget, user monitors in UI): use task node with agentId - NO agent node.\n'
      || E'• Need BOTH tracking + DAG result: use task node WITHOUT agentId (tracking only), then an agent node (synchronous execution), then update_task to mark completed.\n\n'
      || E'When emitting via set_plan, wrap the config under ''task'': { operation, taskId, title, instructions, priority, agentId, reviewerAgentId, status, search, limit }.',
    parameters = '{
      "operation":       {"type": "string", "required": true, "description": "One of: create_task, get_task, update_task, delete_task, list_tasks"},
      "taskId":          {"type": "string", "required": false, "description": "UUID of the task (required for get_task, update_task, delete_task). Supports templates e.g. {{core:step.output.task.id}}"},
      "title":           {"type": "string", "required": false, "description": "Task title (required for create_task, optional for update_task). Supports templates."},
      "instructions":    {"type": "string", "required": false, "description": "Task instructions/description. Supports templates."},
      "priority":        {"type": "string", "required": false, "description": "One of: low, normal, high, urgent. Default: normal"},
      "agentId":         {"type": "string", "required": false, "description": "UUID of the agent to assign. ⚠ If set, the agent is AUTO-TRIGGERED asynchronously - the DAG continues without waiting. Omit agentId if you want to control execution via a separate agent node."},
      "reviewerAgentId": {"type": "string", "required": false, "description": "UUID of the reviewer agent. When the assignee marks the task done, it moves to in_review for this agent."},
      "status":          {"type": "string", "required": false, "description": "Status filter for list_tasks, or new status for update_task. Values: pending, in_progress, in_review, completed, failed, cancelled"},
      "search":          {"type": "string", "required": false, "description": "Search term for list_tasks. Supports templates."},
      "limit":           {"type": "number", "required": false, "default": 50, "description": "Max results for list_tasks (1-200)"}
    }'::jsonb,
    outputs = '{
      "node_type":  {"type": "string",  "description": "Always ''TASK''"},
      "operation":  {"type": "string",  "description": "The operation that was executed"},
      "success":    {"type": "boolean", "description": "Whether the operation succeeded"},
      "task":       {"type": "object",  "description": "The task object (create/get/update). Fields: id, title, instructions, status (starts PENDING if agentId set), priority, assignedTo, reviewerAgentId, createdAt, result (null until agent finishes)"},
      "task_id":    {"type": "string",  "description": "The deleted task ID (delete_task only)"},
      "tasks":      {"type": "array",   "description": "Array of task objects (list_tasks only)"},
      "count":      {"type": "number",  "description": "Number of tasks in current page (list_tasks only)"},
      "total":      {"type": "number",  "description": "Total matching tasks (list_tasks only)"}
    }'::jsonb,
    concepts = '[
      {"title": "Auto-trigger (agentId set)", "body": "create_task with agentId dispatches the agent asynchronously after DB commit. The DAG node returns immediately - task.status will be PENDING. The agent runs in the background: PENDING → IN_PROGRESS → COMPLETED/FAILED. You cannot use the agent result in downstream nodes because the DAG has already moved on."},
      {"title": "Tracking-only (no agentId)", "body": "create_task without agentId creates a PENDING task record with no automatic execution. Use this when you want a separate agent node to do the work synchronously (result available in the DAG), and the task node is for board tracking only. Chain: task_create(no agentId) → agent_node → update_task(completed)."},
      {"title": "Double-execution anti-pattern", "body": "NEVER: task_create(agentId=X) → agent_node(X). Agent X runs twice - once from the task auto-trigger (async, result in task record) and once from the agent node (sync, result in DAG). The two executions are independent and may produce different results."},
      {"title": "Task lifecycle", "body": "Status flow: PENDING → IN_PROGRESS (agent acquires lock) → IN_REVIEW (if reviewerAgentId set) → COMPLETED or FAILED. update_task can set status to completed, failed, or cancelled."}
    ]'::jsonb,
    examples = '[
      {"title": "Fire-and-forget (async, no DAG result)", "body": "workflow(action=''add_node'', type=''task'', label=''Assign Research'', params={operation: ''create_task'', title: ''Research {{trigger:start.output.topic}}'', agentId: ''<uuid>'', priority: ''high''})"},
      {"title": "Tracking + DAG result (recommended pattern)", "body": "1. workflow(action=''add_node'', type=''task'', label=''Track Research'', params={operation: ''create_task'', title: ''Research''})\n2. workflow(action=''add_node'', type=''agent'', label=''Research'', agentConfigId=''<uuid>'', prompt=''...'', connect_after=''Track Research'')\n3. workflow(action=''add_node'', type=''task'', label=''Done Research'', params={operation: ''update_task'', taskId: ''{{core:track_research.output.task.id}}'', status: ''completed''}, connect_after=''Research'')"},
      {"title": "DAG result only (simplest - no task board)", "body": "workflow(action=''add_node'', type=''agent'', label=''Research'', agentConfigId=''<uuid>'', prompt=''...'')  - Use agent nodes directly when you need the output in downstream nodes and don''t need task board tracking."}
    ]'::jsonb,
    updated_at = NOW()
WHERE type = 'task';
