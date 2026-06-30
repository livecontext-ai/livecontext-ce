-- V340: Per-agent opt-in toggle for the shared task backlog.
--
-- Before this column, EVERY agent was served the workspace's unassigned task
-- backlog on wake-up:
--   * the schedule-fire prompt (ScheduledTaskPromptBuilder) listed claimable
--     backlog items,
--   * the system-prompt task-summary block (AgentTaskService.getTaskSummaryForPrompt
--     -> TaskSummaryResponse) advertised the backlog count + a `claim` hint in
--     EVERY agent conversation, and
--   * the MCP `agent(action='claim'|'backlog')` actions were open to any agent.
-- That meant an agent with no relationship to a task -- and possibly the wrong
-- tools for it -- was nudged to pick it up, even mid interactive chat.
--
-- backlog_enabled makes backlog participation EXPLICIT and OPT-IN:
--   false (default) -> the agent is never served, and cannot autonomously claim,
--                      shared backlog work. Tasks DIRECTLY assigned to it (inbox)
--                      and tasks it must review are unaffected -- those are targeted.
--   true            -> today's behaviour: the agent sees the backlog on wake-up
--                      and may claim items (a designated "backlog worker").
--
-- Human board actions (manually assigning/claiming a backlog task to an agent
-- via the task board) are NOT governed by this flag -- that is an explicit
-- human override. See ScheduledTaskPromptBuilder, AgentTaskService,
-- AgentDelegationModule.

ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS backlog_enabled BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN agent.agents.backlog_enabled IS
    'Opt-in (default false): when true the agent is served the shared task backlog on wake-up (schedule prompt + system-prompt task summary) and may autonomously claim backlog items via the agent tool. Directly-assigned (inbox) and review tasks are unaffected. Human board claim/assign overrides this flag.';
