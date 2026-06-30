-- V341: CONCURRENTLY index on agent.agent_execution_tool_calls.execution_id (additive).
--
-- The fleet / agent metrics readers (AgentMetricsQueryService.getToolStats /
-- getResourceStats / getAllToolStatsByAgent / getAllResourceStatsByAgent, served by
-- the /agents/stats batch) join agent_execution_tool_calls -> agent_executions on
-- tc.execution_id = ae.id. That table carried ONLY its PRIMARY KEY (id) - no index
-- on execution_id, and no FK (PostgreSQL does not auto-index foreign keys). So every
-- fleet-stats read had to scan the whole tool-calls table to satisfy the join. The
-- table is append-only execution history and grows monotonically, so the scan cost
-- climbs with usage. This btree on execution_id lets the join probe by key.
--
-- (Supersedes the stale V210 note "the existing index on execution_id is already
-- sufficient" - verified against prod 2026-06-15: the table's only index was the id PK.)
--
-- Runs OUTSIDE Flyway's transaction wrapper because CREATE INDEX CONCURRENTLY cannot
-- be issued inside a transaction. The marker below tells Flyway to skip the
-- BEGIN/COMMIT it would normally emit around the script.
--
-- Lock posture: SHARE UPDATE EXCLUSIVE on agent_execution_tool_calls - concurrent
-- reads/writes proceed during the build. Fast at current size (~18k rows / 39 MB);
-- CONCURRENTLY keeps it safe as the history grows.
--
-- On crash: PG marks the index INVALID. Recovery: DROP INDEX agent.idx_agent_exec_tool_calls_execution_id;
-- then re-run (use flyway:repair first if version 341 is already recorded).
--
-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_exec_tool_calls_execution_id
    ON agent.agent_execution_tool_calls (execution_id);
