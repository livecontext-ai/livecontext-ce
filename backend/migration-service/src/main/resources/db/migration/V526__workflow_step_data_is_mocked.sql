-- V526: a queryable flag for a step whose result was served by the per-node mock mode.
--
-- A mocked node still writes its workflow_step_data row with the tool's REAL tool_id and a real
-- status, and until now the only trace of the mock was `__mocked__` inside the step PAYLOAD (behind
-- output_storage_id), which no SQL can reach. So the Grafana "Catalog - Tool health" row counted a
-- mock as a real call: a mock with source `error` exists precisely to produce a FAILED step for a
-- tool that works, and put a healthy tool in the BROKEN queue; a `static` / `catalog_example` mock
-- counted as a success and diluted a genuinely broken tool's failure rate.
--
-- Rows written before this migration read false, whether they were mocked or not: the flag is only
-- known in the payload, and reading every payload back from storage is not worth it for a
-- dashboard whose reach is a few days. The ambiguity therefore ages out of the dashboard's
-- 7-day ceiling on its own.
--
-- NOT NULL DEFAULT false is a catalog-only change on PostgreSQL 11+: no table rewrite on the
-- ~250k-row production table. It still takes an ACCESS EXCLUSIVE lock for that instant, and a
-- lock request that WAITS blocks every step insert queued behind it, so the wait is bounded: past
-- 10s the migration fails and the deploy is retried, rather than stalling every running workflow.
SET LOCAL lock_timeout = '10s';

ALTER TABLE orchestrator.workflow_step_data
    ADD COLUMN IF NOT EXISTS is_mocked BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN orchestrator.workflow_step_data.is_mocked IS
    'True when the per-node mock mode served this step instead of a real execution (the __mocked__ '
    'marker of the payload, made queryable). Rows written before V526 read false.';
