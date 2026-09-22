-- V509: time indexes for the Grafana "Catalog - Tool health" row.
--
-- The row answers "which catalog tool is failing" out of two tables that already record it and
-- that nothing reads this way:
--   orchestrator.workflow_step_data   (a row per node completion)
--   agent.agent_execution_tool_calls  (a row per agent tool call)
--
-- Neither has an index on its time column. orchestrator.workflow_step_data is indexed on
-- (run_id, ...), (workflow_run_id, ...), (tenant_id) and a partial one on organization_id - the
-- execution engine's own lookups - and
-- agent_execution_tool_calls only on (execution_id). Every panel in that row filters on a time
-- window and nothing else, so without these the window can only be found by reading the table.
--
-- WHAT WAS MEASURED, including the part that does not flatter these indexes. On a 200,000-row
-- workflow_step_data spread over 60 days, asking for the last 24 hours (about 3,400 rows), the
-- planner prefers a PARALLEL SEQ SCAN and beats the index: 616 ms without, 932 ms with. An index
-- on a timestamp only pays once the table is large enough relative to the window that reading all
-- of it stops being cheap, and 200k rows is not that point. These are shipped because production
-- grows past it - the table takes a row per node completion and is swept only by the execution-log
-- retention job, which is off by default (three switches, all required) - not because a benefit
-- was observed at the size measured here. If that job is armed with a short window and the table
-- stays small, these indexes are dead weight; they are cheap dead weight, see below.
--
-- Plain, on the time column, NOT partial on the failures. The main panel needs the SUCCESSFUL
-- calls too: without them there is no failure rate, and a failure rate is what separates "this
-- tool is broken" from "this tool is used a thousand times a day". A partial index on
-- status = 'FAILED' would serve three of the eight panels and leave the most important one reading
-- the whole table anyway.
--
-- Cheap to maintain: both columns are monotonically increasing, so every insert lands in the
-- rightmost index page. That is the least expensive shape of btree maintenance there is, which is
-- what makes an index worth shipping ahead of the size that needs it.
--
-- Lock posture: CONCURRENTLY, so SHARE UPDATE EXCLUSIVE - concurrent reads and writes proceed
-- during the build. The convention is V149/V150 (on credit_ledger) and V155/V168 (on this very
-- table).
--
-- On crash: PG marks the index INVALID. Recovery: drop it and re-run V509 (flyway:repair if
-- needed).

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wsd_start_time
    ON orchestrator.workflow_step_data (start_time);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_tool_calls_created_at
    ON agent.agent_execution_tool_calls (created_at);
