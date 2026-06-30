-- ============================================================================
-- V52: Typed execution refactor for catalog API tools
-- ============================================================================
-- Adds the three columns required by the centralized ToolExecutionOrchestrator:
--
--   1. execution_spec  JSONB  - declarative request/response handling per endpoint
--                                (mode, bodyType, response.type, async config, etc.)
--   2. output_schema   JSONB  - typed output fields, same shape as OutputFieldDef.
--                                Single source of truth consumed by:
--                                  - OutputProjector (runtime validation)
--                                  - /api/node-definitions (frontend variable picker)
--                                  - LLM agent prompts
--   3. execution_mode  VARCHAR(20)  - denormalized from execution_spec.mode
--                                     for cheap WHERE/index queries
--
-- All three are nullable for the migration window. After the JSON sweep
-- (phase 8+) they will become NOT NULL and the importer will fail-fast on
-- any tool missing them.
-- ============================================================================

SET search_path TO catalog, public;

ALTER TABLE api_tools
    ADD COLUMN IF NOT EXISTS execution_spec JSONB,
    ADD COLUMN IF NOT EXISTS output_schema  JSONB,
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(20);

-- Index on execution_mode so the orchestrator startup scan can group tools
-- by mode without a sequential scan once the table grows.
CREATE INDEX IF NOT EXISTS idx_api_tools_execution_mode
    ON api_tools(execution_mode);

-- Sanity check constraint: execution_mode must match the enumerated set if present.
ALTER TABLE api_tools
    DROP CONSTRAINT IF EXISTS check_api_tools_execution_mode;
ALTER TABLE api_tools
    ADD CONSTRAINT check_api_tools_execution_mode
    CHECK (execution_mode IS NULL OR execution_mode IN
        ('sync', 'async_poll', 'upload', 'streaming', 'webhook'));

COMMENT ON COLUMN api_tools.execution_spec IS
    'Declarative execution contract: { mode, request: { bodyType, multipartFields? }, response: { type, binaryHandling? }, async?, webhook? }. Consumed by ToolExecutionOrchestrator.';
COMMENT ON COLUMN api_tools.output_schema IS
    'Typed output fields as a JSONB array of { key, type, description, children? }. Same shape as OutputFieldDef. Source of truth for /api/node-definitions and runtime OutputProjector.';
COMMENT ON COLUMN api_tools.execution_mode IS
    'Denormalized from execution_spec.mode for indexed lookups. One of: sync | async_poll | upload | streaming | webhook.';
