-- V524: whether an agent reaches the person outside the app at all.
--
-- V523 let an agent pick WHICH workspace destination its permission requests
-- and questions go to (NULL = the workspace default). This is the switch
-- above it, the same "on/off card" as the agent's webhook or schedule:
--
--   true   the agent's requests and questions are sent to its destination
--          (V523), when nobody is watching the run. Every existing agent, so
--          nothing changes for them.
--   false  nothing leaves the app: a permission request is not delivered (the
--          action is not done) and a question is answered by assumption,
--          exactly as for a workspace with no channel connected.

ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS chat_channel_enabled BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN agent.agents.chat_channel_enabled IS
    'Whether this agent''s permission requests and questions are sent to a chat outside the app '
    '(to chat_channel_link_id, or the workspace default). false = nothing leaves the app. V524.';
