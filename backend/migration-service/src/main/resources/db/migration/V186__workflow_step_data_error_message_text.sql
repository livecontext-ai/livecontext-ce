-- V186 - workflow_step_data.error_message: VARCHAR(1000) → TEXT.
--
-- Symptom: a Google Analytics 404 in prod returned a ~4 KB HTML error page as
-- the upstream response body, which StepNode propagates verbatim through
-- StepExecutionResult.message(). The INSERT into workflow_step_data failed
-- with "value too long for type character varying(1000)", and the persistence
-- layer (StepDataPersistenceService.recordStep) silently swallowed the
-- DataIntegrityViolationException - leaving the failed step entirely absent
-- from the DB. The cascading SKIPPED row for the successor (whose
-- error_message is null) persisted fine, so the UI showed a successor SKIPPED
-- with no apparent cause and no failed predecessor.
--
-- See orchestrator-service.log 2026-05-11 11:46:15.727 on app-host, run
-- run_<id>, node mcp:run_report_analytics.
--
-- Any MCP / HTTP node receiving an HTML error page (Google, Cloudflare,
-- Nginx, …) is exposed to the same silent-drop. Switching to TEXT removes
-- the column-length ceiling; persistence stops being a hidden failure mode
-- and the UI surfaces the real error.
--
-- Postgres ALTER COLUMN TYPE VARCHAR(N) -> TEXT is a metadata-only operation
-- (no table rewrite, no data conversion), so it is safe on the live table.

ALTER TABLE orchestrator.workflow_step_data
    ALTER COLUMN error_message TYPE TEXT;
