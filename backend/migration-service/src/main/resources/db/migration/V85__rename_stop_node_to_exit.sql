-- Rename "stop" node type to "exit" in node_type_documentation
-- The Exit node ends execution along a branch; other parallel branches (fork, split) continue normally.
-- Previously called "Stop" which was misleading as it implied global workflow termination.

UPDATE node_type_documentation
SET type = 'exit',
    label = 'Exit',
    description = 'Ends execution along this branch. Other parallel branches (fork, split) continue normally. The workflow completes when all remaining branches finish.',
    outputs = '{"exited_at": {"type": "datetime", "description": "ISO timestamp when exit was triggered"}, "reason": {"type": "string", "description": "Configured or default reason for exiting"}, "status": {"type": "string", "description": "Final status: exited, completed"}}'::jsonb,
    examples = '["workflow_builder(action=''add_node'', type=''exit'', label=''Exit'', connect_after=''Check Error'') - End this branch. Other parallel branches continue.", "workflow_builder(action=''add_node'', type=''exit'', label=''Skip Item'', params={reason: ''Invalid data''}, connect_after=''Validate'') - Exit with a reason."]'::jsonb,
    keywords = '["exit","end","terminate","halt","branch","leave"]'::jsonb,
    updated_at = NOW()
WHERE type = 'stop';
