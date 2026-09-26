-- V523: where an agent's permission requests and questions are sent.
--
-- A workspace connects chat destinations (orchestrator.chat_channel_links,
-- V517) and marks one as its default. Until now every agent used that default:
-- the support agent and the finance agent reached the same chat. This column
-- lets an agent name one of the workspace's destinations instead, the way a
-- node names a credential.
--
--   NULL  the workspace default (unchanged behaviour for every existing agent)
--   uuid  that destination, and only that one
--
-- No foreign key: the link lives in the orchestrator schema, and a service only
-- reads its own schema (cross-schema constraints are not used). The
-- orchestrator checks the id at delivery time, against the request's own
-- workspace, so an id from another workspace or a disconnected destination is
-- never used: the request is reported as not delivered rather than sent to a
-- chat nobody chose.

ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS chat_channel_link_id UUID;

COMMENT ON COLUMN agent.agents.chat_channel_link_id IS
    'Chat destination (orchestrator.chat_channel_links.id) for this agent''s permission '
    'requests and questions. NULL = the workspace default destination. V523.';
