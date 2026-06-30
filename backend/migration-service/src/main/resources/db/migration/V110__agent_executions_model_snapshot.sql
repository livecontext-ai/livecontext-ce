-- Add model_snapshot JSONB to agent_executions so cost calculation
-- survives mid-run model deprecation and retroactive price edits.
--
-- Snapshot schema (_v:1):
--   { _v: 1, provider, model_id, price_input, price_output,
--     credits_input, credits_output, canonical_id, bundle_version,
--     markup, captured_at }  -- captured_at is ISO UTC

SET lock_timeout = '10s';
SET statement_timeout = '60s';

ALTER TABLE agent.agent_executions
    ADD COLUMN IF NOT EXISTS model_snapshot JSONB;

COMMENT ON COLUMN agent.agent_executions.model_snapshot IS
    'Model pricing/config at execution start. _v:1 = {provider, model_id, price_input, price_output, credits_input, credits_output, canonical_id, bundle_version, markup, captured_at}. NULL for rows created before V110.';
