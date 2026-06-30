-- V321: Add parent_conversation_id column to agent_executions.
--
-- Sub-agent executions are persisted under their OWN conversation_id (the
-- dedicated conversation minted per sub-agent), and only carry a link to the
-- PARENT AGENT entity via caller_agent_entity_id - never to the parent's
-- CONVERSATION. As a result, a conversation-scoped observability view (which
-- filters executions by conversation_id) could never surface the tool calls and
-- executions of sub-agents the parent spawned: they lived under a different
-- conversation with no back-reference.
--
-- This column records the conversation that SPAWNED the sub-agent, so the
-- observability layer can fetch the full execution tree rooted at a parent
-- conversation. Nullable - only populated on sub-agent spawns; NULL for root
-- (REST/CLI/chat/workflow) executions.
ALTER TABLE agent.agent_executions ADD COLUMN IF NOT EXISTS parent_conversation_id VARCHAR(255);

-- Index for querying sub-agent executions by the conversation that spawned them.
CREATE INDEX IF NOT EXISTS idx_agent_executions_parent_conversation_id
    ON agent.agent_executions (parent_conversation_id) WHERE parent_conversation_id IS NOT NULL;
