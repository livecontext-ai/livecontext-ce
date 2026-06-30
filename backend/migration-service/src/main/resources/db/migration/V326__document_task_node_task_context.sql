-- ============================================================================
-- V326: Document the task node's taskContext parameter
--   taskContext has been supported by the execution engine since the node was
--   introduced (Core.TaskConfig + TaskNode.executeCreate resolve template
--   expressions inside its values and pass it to task creation), but it was
--   missing from node_type_documentation (V292) - agents could not discover it.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
    parameters = parameters || '{
      "taskContext": {"type": "object", "required": false, "description": "Arbitrary key/value context attached to the task (create_task only). String values support templates e.g. {\"runUrl\": \"{{trigger:start.output.url}}\"}. Visible to the assignee agent alongside the instructions."}
    }'::jsonb,
    description = replace(
        description,
        '''task'': { operation, taskId, title, instructions, priority, agentId, reviewerAgentId, status, search, limit }',
        '''task'': { operation, taskId, title, instructions, priority, agentId, reviewerAgentId, status, search, limit, taskContext }'),
    examples = examples || '[
      {"title": "Create with context payload", "body": "workflow(action=''add_node'', type=''task'', label=''Track Order'', params={operation: ''create_task'', title: ''Handle order {{trigger:start.output.order_id}}'', taskContext: {orderId: ''{{trigger:start.output.order_id}}'', source: ''webhook''}}) - taskContext key/values are attached to the task and shown to the assignee agent alongside the instructions."}
    ]'::jsonb,
    updated_at = NOW()
WHERE type = 'task';
