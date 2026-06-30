-- Clean up orphaned agent metrics data from deleted agents.
-- Uses schema-qualified names and IF EXISTS for portability.
SET search_path TO agent, public;

DO $$
BEGIN
    -- 1. Delete per-agent tool call stats for agents that no longer exist
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'agent' AND table_name = 'agent_tool_call_stats_by_agent_live') THEN
        DELETE FROM agent.agent_tool_call_stats_by_agent_live
        WHERE agent_entity_id NOT IN (SELECT id FROM agent.agents);
    END IF;

    -- 2. Delete sub-agent call stats referencing deleted agents
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'agent' AND table_name = 'agent_sub_agent_call_stats_live') THEN
        DELETE FROM agent.agent_sub_agent_call_stats_live
        WHERE caller_agent_id NOT IN (SELECT id FROM agent.agents)
           OR callee_agent_id NOT IN (SELECT id FROM agent.agents);
    END IF;

    -- 3. Delete execution children for orphaned executions
    DELETE FROM agent.agent_execution_messages
    WHERE execution_id IN (
        SELECT id FROM agent.agent_executions
        WHERE agent_entity_id IS NOT NULL
          AND agent_entity_id NOT IN (SELECT id FROM agent.agents)
    );

    DELETE FROM agent.agent_execution_tool_calls
    WHERE execution_id IN (
        SELECT id FROM agent.agent_executions
        WHERE agent_entity_id IS NOT NULL
          AND agent_entity_id NOT IN (SELECT id FROM agent.agents)
    );

    DELETE FROM agent.agent_execution_iterations
    WHERE execution_id IN (
        SELECT id FROM agent.agent_executions
        WHERE agent_entity_id IS NOT NULL
          AND agent_entity_id NOT IN (SELECT id FROM agent.agents)
    );

    -- 4. Delete orphaned executions themselves
    DELETE FROM agent.agent_executions
    WHERE agent_entity_id IS NOT NULL
      AND agent_entity_id NOT IN (SELECT id FROM agent.agents);
END $$;
