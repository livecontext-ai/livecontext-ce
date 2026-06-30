-- V299: Per-agent extension seam for the synchronous tool-authorization gate.
--
-- Default false = current behaviour: the gate applies ONLY to interactive general
-- conversations; agents launched via workflow / task / sub-agent are exempt.
--
-- This column is the persisted source a future iteration will thread into
-- credentials.__requireToolAuthorization__ so that, when true, the gate ALSO applies to
-- this agent outside chat (finer control) - with no rewiring. v1 leaves every agent at
-- false. See ToolAuthorizationScopeResolver.

ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS require_tool_authorization BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN agent.agents.require_tool_authorization IS
    'Extension seam (default false): when true, the tool-authorization gate also applies to this agent outside interactive chat (workflow/task/sub-agent). v1 leaves all agents at false = exempt outside chat.';
